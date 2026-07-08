package ca.esmcelroy.schema2class.parser.xsd

import ca.esmcelroy.schema2class.codegen.kotlin.KotlinCodegen
import ca.esmcelroy.schema2class.core.ir.PrimitiveType
import ca.esmcelroy.schema2class.core.ir.SchemaModel
import ca.esmcelroy.schema2class.core.ir.TypeDefinition
import ca.esmcelroy.schema2class.core.ir.TypeRef
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * Round-trip suite: real XSD fixture → IR → generated Kotlin source.
 *
 * Verifies structural validity of the generated source (referential integrity,
 * balanced delimiters, expected declarations). Actual compilation of generated
 * code is covered by the integration test suite (schema2class-0p2).
 */
class XsdRoundTripTest {

    private val parser = XsdParser()
    private val codegen = KotlinCodegen()

    private fun parseFixture(resource: String, packageName: String): SchemaModel =
        javaClass.getResourceAsStream(resource).shouldNotBeNull().use {
            parser.parse(it, packageName)
        }

    // ── Shared structural checks ──────────────────────────────────────────────

    /** Every same-package TypeRef.Named in the model must resolve to a defined type. */
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
            // A data class with zero constructor parameters is invalid Kotlin
            assert(!source.contains(Regex("""data class \w+\(\s*\)"""))) {
                "$path contains a data class with an empty constructor:\n$source"
            }
        }
    }

    private fun withBalancedDelimiters(path: String, source: String) {
        // Strip string literals and comments crudely: good enough for generated code
        val stripped = source
            .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\"([^\"\\\\]|\\\\.)*\""), "")
            .replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        for ((open, close) in listOf('{' to '}', '(' to ')', '<' to '>')) {
            val opens = stripped.count { it == open }
            val closes = stripped.count { it == close }
            assert(opens == closes) { "$path has unbalanced $open$close ($opens vs $closes)" }
        }
    }

    // ── Maven POM 4.0.0 (real-world, Apache-2.0, 36 named complex types) ─────

    @Test
    fun `maven pom xsd parses with expected model shape`() {
        val model = parseFixture("/maven-4.0.0.xsd", "org.apache.maven.pom")

        model.namespace shouldBe "http://maven.apache.org/POM/4.0.0"
        val names = model.types.map { it.kotlinName }
        names shouldContain "Model"
        names shouldContain "Dependency"
        names shouldContain "Build"
        names shouldContain "Parent"
        names shouldContain "Plugin"
        // 36 named complex types plus generated inline wrapper types
        model.types.size shouldBeGreaterThan 36
    }

    @Test
    fun `maven pom model type has expected optional properties`() {
        val model = parseFixture("/maven-4.0.0.xsd", "org.apache.maven.pom")
        val modelType = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.kotlinName == "Model" }.shouldNotBeNull()

        val groupId = modelType.properties.find { it.schemaName == "groupId" }.shouldNotBeNull()
        groupId.type shouldBe TypeRef.Primitive(PrimitiveType.STRING)
        groupId.nullable shouldBe true

        val parent = modelType.properties.find { it.schemaName == "parent" }.shouldNotBeNull()
        parent.type shouldBe TypeRef.Named("Parent")
    }

    @Test
    fun `maven pom model has no dangling type references`() {
        val model = parseFixture("/maven-4.0.0.xsd", "org.apache.maven.pom")
        assertReferentialIntegrity(model)
    }

    @Test
    fun `maven pom round-trips to well-formed kotlin source for every type`() {
        val model = parseFixture("/maven-4.0.0.xsd", "org.apache.maven.pom")
        val sources = codegen.generate(model)

        sources.size shouldBe model.types.size
        assertSourcesWellFormed(sources, "org.apache.maven.pom")

        val dependency = sources.getValue("org/apache/maven/pom/Dependency.kt")
        dependency shouldContain "data class Dependency("
        dependency shouldContain "val groupId: String? = null"
        dependency shouldContain "val artifactId: String? = null"
    }

    // ── UNECE-style business document (simpleContent, codelist enum) ─────────

    @Test
    fun `business doc round-trips simpleContent types with value-first constructors`() {
        val model = parseFixture("/business-doc.xsd", "com.example.business")
        assertReferentialIntegrity(model)
        val sources = codegen.generate(model)
        assertSourcesWellFormed(sources, "com.example.business")

        val amount = sources.getValue("com/example/business/AmountType.kt")
        amount shouldContain "data class AmountType("
        amount shouldContain "BigDecimal"
        // Naming policy: Kotlin identifiers follow Kotlin conventions (acronyms like
        // "ID" become "Id"); the wire name "currencyID" is preserved in schemaName
        // for serialization annotations.
        amount shouldContain "val currencyId: String"
        // content property precedes attributes in the constructor
        assert(amount.indexOf("BigDecimal") < amount.indexOf("currencyId")) {
            "content property should be the first constructor parameter:\n$amount"
        }

        val invoice = sources.getValue("com/example/business/InvoiceType.kt")
        invoice shouldContain "val line: List<InvoiceLineType>"
        invoice shouldContain "val documentId: String"
        invoice shouldContain "val attachment: ByteArray? = null"
    }

    @Test
    fun `business doc codelist enum round-trips with serialized-value comments`() {
        val model = parseFixture("/business-doc.xsd", "com.example.business")
        val sources = codegen.generate(model)

        val enumSource = sources.getValue("com/example/business/ActionCodeContentType.kt")
        enumSource shouldContain "enum class ActionCodeContentType"
        enumSource shouldContain "ADDED"
        enumSource shouldContain "// \"1\""
        enumSource shouldContain "DELETED"
        enumSource shouldContain "// \"2\""
    }

    @Test
    fun `business doc constrained simple type round-trips to typealias`() {
        val model = parseFixture("/business-doc.xsd", "com.example.business")
        val sources = codegen.generate(model)

        val alias = sources.getValue("com/example/business/ReferenceIdType.kt")
        alias shouldContain "typealias ReferenceIdType = String"
    }

    // ── Spring beans (real-world: groups, attributeGroups, choice, refs) ─────

    @Test
    fun `spring beans xsd parses groups and round-trips to well-formed source`() {
        val model = parseFixture("/spring-beans.xsd", "org.springframework.beans")

        // beanElements group + beanAttributes attributeGroup must be inlined:
        // the bean element's inline type may not be empty
        val bean = model.types.filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.schemaName == "bean" }.shouldNotBeNull()
        bean.properties.size shouldBeGreaterThan 10
        bean.properties.map { it.schemaName } shouldContain "meta"
        bean.properties.map { it.schemaName } shouldContain "constructor-arg"

        val sources = codegen.generate(model)
        assertSourcesWellFormed(sources, "org.springframework.beans")
    }

    // ── Shiporder (anonymous inline nesting, no namespace) ───────────────────

    @Test
    fun `shiporder round-trips inline anonymous types into named classes`() {
        val model = parseFixture("/shiporder.xsd", "com.example.ship")
        assertReferentialIntegrity(model)
        val sources = codegen.generate(model)
        assertSourcesWellFormed(sources, "com.example.ship")

        val shiporder = sources.getValue("com/example/ship/Shiporder.kt")
        shiporder shouldContain "data class Shiporder("
        shiporder shouldContain "val orderid: String"
        shiporder shouldContain "List<ShiporderItem>"

        val item = sources.getValue("com/example/ship/ShiporderItem.kt")
        item shouldContain "val note: String? = null"
        item shouldContain "val quantity: Long"
        item shouldContain "BigDecimal"
    }
}
