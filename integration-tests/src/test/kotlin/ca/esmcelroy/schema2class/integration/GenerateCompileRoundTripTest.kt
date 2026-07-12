package ca.esmcelroy.schema2class.integration

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import ca.esmcelroy.schema2class.codegen.kotlin.AnnotationMode
import ca.esmcelroy.schema2class.codegen.kotlin.KotlinCodegen
import ca.esmcelroy.schema2class.core.ir.EnumValue
import ca.esmcelroy.schema2class.core.ir.PrimitiveType
import ca.esmcelroy.schema2class.core.ir.SchemaModel
import ca.esmcelroy.schema2class.core.ir.SourceFormat
import ca.esmcelroy.schema2class.core.ir.TypeDefinition
import ca.esmcelroy.schema2class.parser.jsonschema.JsonSchemaParser
import ca.esmcelroy.schema2class.parser.xsd.XsdParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import nl.adaptivity.xmlutil.serialization.XML
import org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationComponentRegistrar
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal

/**
 * End-to-end: parse real schemas → generate Kotlin → compile it with the actual
 * Kotlin compiler → load and instantiate the classes → round-trip documents.
 *
 * JSON document round-trips use Jackson's Kotlin module, which works reflectively
 * on plain data classes. XML document round-trips require an annotation mode
 * (schema2class-9oo / n0g / 1k6) and are added with that work.
 */
@OptIn(ExperimentalCompilerApi::class)
class GenerateCompileRoundTripTest {

    private val codegen = KotlinCodegen()

    private fun fixtureFile(name: String): File =
        File(javaClass.getResource("/$name").shouldNotBeNull().toURI())

    private fun compile(
        sourcesByPath: Map<String, String>,
        withSerializationPlugin: Boolean = false,
    ): KotlinCompilation.Result {
        val compilation = KotlinCompilation().apply {
            sources = sourcesByPath.map { (path, content) ->
                SourceFile.kotlin(path.substringAfterLast('/'), content)
            }
            inheritClassPath = true
            verbose = false
            messageOutputStream = java.io.ByteArrayOutputStream()
            if (withSerializationPlugin) {
                compilerPluginRegistrars = listOf(SerializationComponentRegistrar())
            }
        }
        val result = compilation.compile()
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        return result
    }

    private fun generateAll(models: List<SchemaModel>): Map<String, String> =
        models.flatMap { codegen.generate(it).entries }.associate { it.toPair() }

    // ── XSD: single file with simpleContent and enums ─────────────────────────

    @Test
    fun `business doc generates code that compiles and instantiates`() {
        val model = XsdParser().parse(fixtureFile("business-doc.xsd"), "com.example.business")
        val result = compile(codegen.generate(model))

        val amountClass = result.classLoader.loadClass("com.example.business.AmountType")
        val amount = amountClass.constructors.single { !it.isSynthetic }
            .newInstance(BigDecimal("99.95"), "USD", null)

        amountClass.getMethod("getValue").invoke(amount) shouldBe BigDecimal("99.95")
        amountClass.getMethod("getCurrencyId").invoke(amount) shouldBe "USD"

        val enumClass = result.classLoader.loadClass("com.example.business.ActionCodeContentType")
        val constants = enumClass.enumConstants.map { it.toString() }
        constants shouldBe listOf("ADDED", "DELETED", "CHANGED")
    }

    // ── XSD: multi-file import graph compiles across packages ────────────────

    @Test
    fun `multi-file xsd models compile together and link across packages`() {
        val models = XsdParser().parseWithImports(fixtureFile("multifile/catalog.xsd"))
        models shouldHaveSize 2
        val result = compile(generateAll(models))

        val moneyClass = result.classLoader.loadClass("test.money.MoneyType")
        val money = moneyClass.constructors.single { !it.isSynthetic }.newInstance(BigDecimal("12.50"), "EUR")

        // ItemType lives in test.catalog but its price parameter is test.money.MoneyType —
        // constructing it proves the cross-package import in generated source resolves
        val itemClass = result.classLoader.loadClass("test.catalog.ItemType")
        val item = itemClass.constructors.single { !it.isSynthetic }.newInstance("SKU-1", money)
        itemClass.getMethod("getPrice").invoke(item) shouldBe money
    }

    // ── XSD: real-world scale (Maven POM, ~70 generated files) ───────────────

    @Test
    fun `maven pom generated sources all compile and instantiate`() {
        val model = XsdParser().parse(fixtureFile("maven-4.0.0.xsd"), "org.apache.maven.pom")
        model.types.size shouldBeGreaterThan 36
        val result = compile(codegen.generate(model))

        // Dependency has all-optional properties, so Kotlin also emits a
        // no-arg constructor — construct with every default applied
        val depClass = result.classLoader.loadClass("org.apache.maven.pom.Dependency")
        val dep = depClass.getDeclaredConstructor().newInstance()
        depClass.getMethod("getGroupId").invoke(dep) shouldBe null
    }

    // ── JSON Schema: compile + Jackson document round-trip ───────────────────

    @Test
    fun `telemetry payload compiles and round-trips a real json document`() {
        val model = JsonSchemaParser().parse(
            fixtureFile("telemetry-payload.schema.json"),
            "com.example.telemetry",
        )
        val result = compile(codegen.generate(model))
        val payloadClass = result.classLoader.loadClass("com.example.telemetry.TelemetryPayload")

        val mapper = JsonMapper.builder()
            .addModule(kotlinModule())
            .addModule(JavaTimeModule())
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build()

        val document = """
            {
              "deviceId": "dev-42",
              "recordedAt": "2026-07-04T12:00:00Z",
              "battery": 87.5,
              "status": "degraded",
              "readings": [
                { "sensor": "temp", "value": 21.5, "unit": "fahrenheit" },
                { "sensor": "humidity", "value": 40.0 }
              ]
            }
        """.trimIndent()

        val payload = mapper.readValue(document, payloadClass)

        payloadClass.getMethod("getDeviceId").invoke(payload) shouldBe "dev-42"
        payloadClass.getMethod("getBattery").invoke(payload) shouldBe 87.5
        payloadClass.getMethod("getStatus").invoke(payload).toString() shouldBe "DEGRADED"

        val readings = payloadClass.getMethod("getReadings").invoke(payload) as List<*>
        readings shouldHaveSize 2
        val second = readings[1].shouldNotBeNull()
        // The schema default kicked in for the omitted "unit" field
        second.javaClass.getMethod("getUnit").invoke(second) shouldBe "celsius"

        // Serialize → deserialize → data-class equality proves lossless round-trip
        val serialized = mapper.writeValueAsString(payload)
        val reparsed = mapper.readValue(serialized, payloadClass)
        reparsed shouldBe payload
    }

    // ── JSON Schema: kotlinx.serialization mode with the real compiler plugin ─

    @Test
    fun `kotlinx annotation mode round-trips wire names jackson cannot map`() {
        val model = JsonSchemaParser().parse(
            fixtureFile("telemetry-payload.schema.json"),
            "com.example.kx",
        )
        val kotlinxCodegen = KotlinCodegen(
            KotlinCodegen.Options(annotationMode = AnnotationMode.KOTLINX_SERIALIZATION),
        )
        val result = compile(kotlinxCodegen.generate(model), withSerializationPlugin = true)
        val payloadClass = result.classLoader.loadClass("com.example.kx.TelemetryPayload")

        // The generated companion serializer is discovered reflectively across classloaders
        val payloadSerializer = serializer(payloadClass)
        val json = Json

        val document = """
            {
              "deviceId": "dev-42",
              "recordedAt": "2026-07-04T12:00:00Z",
              "firmware-version": "2.3.1",
              "status": "degraded",
              "readings": [
                { "sensor": "temp", "value": 21.5 }
              ]
            }
        """.trimIndent()

        val payload = json.decodeFromString(payloadSerializer, document).shouldNotBeNull()

        // @SerialName("firmware-version") maps the hyphenated wire name — the case
        // the reflection-based Jackson test had to leave out
        payloadClass.getMethod("getFirmwareVersion").invoke(payload) shouldBe "2.3.1"
        // @SerialName("degraded") on the enum constant maps the exact wire value
        payloadClass.getMethod("getStatus").invoke(payload).toString() shouldBe "DEGRADED"
        // Kotlin default applied for the omitted "unit" field
        val readings = payloadClass.getMethod("getReadings").invoke(payload) as List<*>
        readings[0]!!.javaClass.getMethod("getUnit").invoke(readings[0]) shouldBe "celsius"

        // Encode → decode → equality
        val encoded = json.encodeToString(payloadSerializer, payload)
        json.decodeFromString(payloadSerializer, encoded) shouldBe payload
    }

    @Test
    fun `jackson annotation mode generated json and xml models compile`() {
        val jacksonCodegen = KotlinCodegen(
            KotlinCodegen.Options(annotationMode = AnnotationMode.JACKSON),
        )
        val jsonModel = JsonSchemaParser().parse(
            fixtureFile("telemetry-payload.schema.json"),
            "com.example.jacksonjson",
        )
        val xsdModel = XsdParser().parse(fixtureFile("business-doc.xsd"), "com.example.jacksonxml")

        compile(jacksonCodegen.generate(jsonModel) + jacksonCodegen.generate(xsdModel))
    }

    @Test
    fun `jackson numeric enum serializes numbers and deserializes unknown fallback`() {
        val jacksonCodegen = KotlinCodegen(
            KotlinCodegen.Options(
                annotationMode = AnnotationMode.JACKSON,
                enumUnknownFallback = true,
            ),
        )
        val model = SchemaModel(
            namespace = null,
            packageName = "com.example.numericenum",
            types = listOf(
                TypeDefinition.EnumType(
                    schemaName = "BulbState",
                    kotlinName = "BulbState",
                    documentation = null,
                    values = listOf(
                        EnumValue(serializedValue = "0", kotlinName = "OFF", documentation = null),
                        EnumValue(serializedValue = "1", kotlinName = "ON", documentation = null),
                        EnumValue(serializedValue = "3", kotlinName = "FLASHING", documentation = null),
                    ),
                    baseType = PrimitiveType.INT,
                ),
            ),
            sourceFormat = SourceFormat.JSON_SCHEMA,
        )
        val result = compile(jacksonCodegen.generate(model))
        val enumClass = result.classLoader.loadClass("com.example.numericenum.BulbState")
        val constants = enumClass.enumConstants.associateBy { it.toString() }
        val mapper = JsonMapper.builder().build()

        mapper.writeValueAsString(constants.getValue("OFF")) shouldBe "0"
        mapper.readValue("3", enumClass).toString() shouldBe "FLASHING"
        mapper.readValue("999", enumClass).toString() shouldBe "UNKNOWN"
    }

    // ── XSD: xmlutil mode round-trips an actual XML document ─────────────────

    @Test
    fun `xmlutil mode round-trips an xml document with attributes and text content`() {
        val model = XsdParser().parse(fixtureFile("business-doc.xsd"), "com.example.xml")
        val xmlCodegen = KotlinCodegen(
            KotlinCodegen.Options(annotationMode = AnnotationMode.XMLUTIL),
        )
        val result = compile(xmlCodegen.generate(model), withSerializationPlugin = true)

        val amountClass = result.classLoader.loadClass("com.example.xml.AmountType")
        val xml = XML()
        val amountSerializer = serializer(amountClass)

        val document =
            """<AmountType xmlns="urn:test:business-doc" currencyID="USD">99.95</AmountType>"""
        val amount = xml.decodeFromString(amountSerializer, document).shouldNotBeNull()

        // @XmlValue content property and @XmlElement(false) attribute both mapped
        amountClass.getMethod("getValue").invoke(amount) shouldBe BigDecimal("99.95")
        amountClass.getMethod("getCurrencyId").invoke(amount) shouldBe "USD"

        // Encode: currencyID must come out as an XML attribute, value as text content
        val encoded = xml.encodeToString(amountSerializer, amount)
        encoded shouldContain "currencyID=\"USD\""
        encoded shouldContain ">99.95<"

        xml.decodeFromString(amountSerializer, encoded) shouldBe amount
    }
}
