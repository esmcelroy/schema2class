package io.github.schema2class.parser.jsonschema

import io.github.schema2class.codegen.kotlin.KotlinCodegen
import io.github.schema2class.core.ir.PrimitiveType
import io.github.schema2class.core.ir.SchemaModel
import io.github.schema2class.core.ir.TypeDefinition
import io.github.schema2class.core.ir.TypeRef
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * Round-trip suite: real JSON Schema fixture → IR → generated Kotlin source.
 * Mirrors XsdRoundTripTest on the XSD side. Compilation of generated code is
 * covered by the integration suite (schema2class-0p2).
 */
class JsonSchemaRoundTripTest {

    private val parser = JsonSchemaParser()
    private val codegen = KotlinCodegen()

    private fun parseFixture(resource: String, packageName: String): SchemaModel =
        javaClass.getResourceAsStream(resource).shouldNotBeNull().use {
            parser.parse(it, packageName)
        }

    // ── Shared structural checks (mirror XsdRoundTripTest) ───────────────────

    private fun assertReferentialIntegrity(model: SchemaModel) {
        val defined = model.types.map { it.kotlinName }.toSet()
        val dangling = model.types.flatMap { collectNamedRefs(it) }
            .filter { it.packageName == null && it.name !in defined }
            .map { it.name }
            .distinct()
        dangling.shouldBeEmpty()
    }

    private fun collectNamedRefs(type: TypeDefinition): List<TypeRef.Named> = when (type) {
        is TypeDefinition.ComplexType ->
            (type.properties.map { it.type } + listOfNotNull(type.contentProperty?.type, type.superType))
                .flatMap { namedRefsIn(it) }
        is TypeDefinition.AliasType -> namedRefsIn(type.aliasedType)
        is TypeDefinition.UnionType -> type.variants.flatMap { namedRefsIn(it.type) }
        is TypeDefinition.EnumType -> emptyList()
    }

    private fun namedRefsIn(ref: TypeRef): List<TypeRef.Named> = when (ref) {
        is TypeRef.Named -> listOf(ref)
        is TypeRef.ListOf -> namedRefsIn(ref.element)
        is TypeRef.MapOf -> namedRefsIn(ref.key) + namedRefsIn(ref.value)
        is TypeRef.Primitive -> emptyList()
    }

    private fun assertSourcesWellFormed(sources: Map<String, String>, packageName: String) {
        val pathPrefix = packageName.replace('.', '/') + "/"
        sources.forEach { (path, source) ->
            path shouldStartWith pathPrefix
            source shouldStartWith "package $packageName"
            withBalancedDelimiters(path, source)
            assert(!source.contains(Regex("""data class \w+\(\s*\)"""))) {
                "$path contains a data class with an empty constructor:\n$source"
            }
            // An unescaped ${{ from a schema default would become a string template.
            assert(!source.contains(Regex("""(?<!\\)\$\{\{"""))) {
                "$path contains an unescaped template injection:\n$source"
            }
        }
    }

    private fun withBalancedDelimiters(path: String, source: String) {
        val stripped = source
            .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\"([^\"\\\\]|\\\\.)*\""), "")
            .replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        for ((open, close) in listOf('{' to '}', '(' to ')')) {
            val opens = stripped.count { it == open }
            val closes = stripped.count { it == close }
            assert(opens == closes) { "$path has unbalanced $open$close ($opens vs $closes)" }
        }
    }

    // ── Telemetry payload (the mixed-format embedded-JSON niche shape) ───────

    @Test
    fun `telemetry payload parses with expected model shape`() {
        val model = parseFixture("/telemetry-payload.schema.json", "com.example.telemetry")

        model.namespace shouldBe "https://example.com/schemas/telemetry-payload.json"
        val root = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.kotlinName == "TelemetryPayload" }.shouldNotBeNull()

        val deviceId = root.properties.find { it.schemaName == "deviceId" }.shouldNotBeNull()
        deviceId.nullable shouldBe false
        deviceId.type shouldBe TypeRef.Primitive(PrimitiveType.STRING)

        val battery = root.properties.find { it.schemaName == "battery" }.shouldNotBeNull()
        battery.nullable shouldBe true
        battery.type shouldBe TypeRef.Primitive(PrimitiveType.DOUBLE)

        val readings = root.properties.find { it.schemaName == "readings" }.shouldNotBeNull()
        readings.type shouldBe TypeRef.ListOf(TypeRef.Named("Reading"))
        readings.nullable shouldBe false

        val fw = root.properties.find { it.schemaName == "firmware-version" }.shouldNotBeNull()
        fw.kotlinName shouldBe "firmwareVersion"
    }

    @Test
    fun `telemetry payload has no dangling refs and round-trips to well-formed source`() {
        val model = parseFixture("/telemetry-payload.schema.json", "com.example.telemetry")
        assertReferentialIntegrity(model)

        val sources = codegen.generate(model)
        sources.size shouldBe model.types.size
        assertSourcesWellFormed(sources, "com.example.telemetry")

        val payload = sources.getValue("com/example/telemetry/TelemetryPayload.kt")
        payload shouldContain "data class TelemetryPayload("
        payload shouldContain "val deviceId: String"
        payload shouldContain "val battery: Double? = null"
        payload shouldContain "List<Reading>"
    }

    @Test
    fun `telemetry enum and union definitions round-trip`() {
        val model = parseFixture("/telemetry-payload.schema.json", "com.example.telemetry")
        val sources = codegen.generate(model)

        val status = sources.getValue("com/example/telemetry/Status.kt")
        status shouldContain "enum class Status"
        status shouldContain "OK"
        status shouldContain "DEGRADED"
        status shouldContain "OFFLINE"

        val threshold = sources.getValue("com/example/telemetry/Threshold.kt")
        threshold shouldContain "sealed class Threshold"

        val union = model.types.filterIsInstance<TypeDefinition.UnionType>()
            .find { it.kotlinName == "Threshold" }.shouldNotBeNull()
        union.variants shouldHaveSize 2
        union.variants.map { it.type } shouldContain TypeRef.Primitive(PrimitiveType.DOUBLE)
        union.variants.map { it.type } shouldContain TypeRef.Primitive(PrimitiveType.STRING)
    }

    @Test
    fun `string default value round-trips as a quoted kotlin literal`() {
        val model = parseFixture("/telemetry-payload.schema.json", "com.example.telemetry")
        val sources = codegen.generate(model)

        val reading = sources.getValue("com/example/telemetry/Reading.kt")
        reading shouldContain "val unit: String? = \"celsius\""
    }

    @Test
    fun `string default value with dollar sign round-trips as escaped kotlin literal`() {
        val model = parser.parse(
            """
            {
              "type": "object",
              "properties": {
                "token": { "type": "string", "default": "${'$'}{{ github.token }}" }
              }
            }
            """.trimIndent().byteInputStream(),
            "com.example.defaults",
        )
        val sources = codegen.generate(model)
        assertSourcesWellFormed(sources, "com.example.defaults")

        val root = sources.getValue("com/example/defaults/Root.kt")
        root shouldContain "val token: String? = \"\\${'$'}{{ github.token }}\""
    }

    // ── Recursive schema ──────────────────────────────────────────────────────

    @Test
    fun `recursive schema terminates and self-reference round-trips`() {
        val model = parseFixture("/tree.schema.json", "com.example.tree")
        assertReferentialIntegrity(model)

        val node = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.kotlinName == "Node" }.shouldNotBeNull()
        node.properties.find { it.schemaName == "children" }.shouldNotBeNull()
            .type shouldBe TypeRef.ListOf(TypeRef.Named("Node"))

        val sources = codegen.generate(model)
        assertSourcesWellFormed(sources, "com.example.tree")
        sources.getValue("com/example/tree/Node.kt") shouldContain "List<Node>"
    }

    // ── GitHub Action schema (real-world, draft-07, 30 KB) ───────────────────

    @Test
    fun `github action schema parses without throwing and yields expected definitions`() {
        val model = parseFixture("/github-action.json", "io.github.action")

        model.types.size shouldBeGreaterThan 5
        val names = model.types.map { it.kotlinName }
        names shouldContain "ExpressionSyntax"
        names shouldContain "RunsJavascript"
        names shouldContain "RunsComposite"
        names shouldContain "RunsDocker"
    }

    @Test
    fun `github action schema round-trips to well-formed source for every type`() {
        val model = parseFixture("/github-action.json", "io.github.action")
        val sources = codegen.generate(model)

        sources.size shouldBe model.types.size
        assertSourcesWellFormed(sources, "io.github.action")
    }
}
