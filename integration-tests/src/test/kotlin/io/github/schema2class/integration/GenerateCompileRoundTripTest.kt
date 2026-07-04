package io.github.schema2class.integration

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.github.schema2class.codegen.kotlin.KotlinCodegen
import io.github.schema2class.core.ir.SchemaModel
import io.github.schema2class.parser.jsonschema.JsonSchemaParser
import io.github.schema2class.parser.xsd.XsdParser
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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

    private fun compile(sourcesByPath: Map<String, String>): KotlinCompilation.Result {
        val compilation = KotlinCompilation().apply {
            sources = sourcesByPath.map { (path, content) ->
                SourceFile.kotlin(path.substringAfterLast('/'), content)
            }
            inheritClassPath = true
            verbose = false
            messageOutputStream = java.io.ByteArrayOutputStream()
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
}
