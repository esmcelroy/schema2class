package io.github.schema2class.core.ir

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InheritanceFlattenerTest {

    private fun prop(name: String, type: TypeRef = TypeRef.Primitive(PrimitiveType.STRING)) =
        PropertyDefinition(
            schemaName = name,
            kotlinName = name,
            type = type,
            nullable = false,
            defaultValue = null,
            documentation = null,
        )

    private fun complex(
        name: String,
        props: List<PropertyDefinition>,
        superType: TypeRef? = null,
        contentProperty: PropertyDefinition? = null,
    ) = TypeDefinition.ComplexType(
        schemaName = name,
        kotlinName = name,
        documentation = null,
        properties = props,
        superType = superType,
        contentProperty = contentProperty,
    )

    private fun model(pkg: String, vararg types: TypeDefinition) = SchemaModel(
        namespace = null,
        packageName = pkg,
        types = types.toList(),
        sourceFormat = SourceFormat.XSD,
    )

    private fun List<SchemaModel>.complexType(pkg: String, name: String): TypeDefinition.ComplexType =
        first { it.packageName == pkg }.types
            .filterIsInstance<TypeDefinition.ComplexType>()
            .find { it.kotlinName == name }
            .shouldNotBeNull()

    @Test
    fun `extension chain flattens parent properties first`() {
        val base = complex("Base", listOf(prop("id"), prop("createdAt")))
        val child = complex("Child", listOf(prop("name")), superType = TypeRef.Named("Base"))

        val result = InheritanceFlattener.flatten(listOf(model("p", base, child)))

        val flatChild = result.complexType("p", "Child")
        flatChild.properties.map { it.schemaName } shouldBe listOf("id", "createdAt", "name")
        // superType kept as provenance
        flatChild.superType shouldBe TypeRef.Named("Base")
        // base untouched
        result.complexType("p", "Base").properties.map { it.schemaName } shouldBe
            listOf("id", "createdAt")
    }

    @Test
    fun `three-level chain flattens transitively regardless of declaration order`() {
        val grandchild = complex("GrandChild", listOf(prop("c")), superType = TypeRef.Named("Child"))
        val child = complex("Child", listOf(prop("b")), superType = TypeRef.Named("Base"))
        val base = complex("Base", listOf(prop("a")))

        val result = InheritanceFlattener.flatten(listOf(model("p", grandchild, child, base)))

        result.complexType("p", "GrandChild").properties.map { it.schemaName } shouldBe
            listOf("a", "b", "c")
    }

    @Test
    fun `cross-model supertype flattens using the reference package`() {
        val base = complex("CodeType", listOf(prop("listID")), contentProperty = prop("value"))
        val child = complex(
            "QualifiedCode",
            listOf(prop("qualifier")),
            superType = TypeRef.Named("CodeType", "com.unqualified"),
        )

        val result = InheritanceFlattener.flatten(
            listOf(
                model("com.qualified", child),
                model("com.unqualified", base),
            ),
        )

        val flat = result.complexType("com.qualified", "QualifiedCode")
        flat.properties.map { it.schemaName } shouldBe listOf("listID", "qualifier")
        flat.contentProperty.shouldNotBeNull().schemaName shouldBe "value"
    }

    @Test
    fun `child redeclaration overrides parent property in parent position`() {
        val base = complex("Base", listOf(prop("id"), prop("code", TypeRef.Primitive(PrimitiveType.STRING))))
        val child = complex(
            "Child",
            listOf(prop("code", TypeRef.Primitive(PrimitiveType.INT)), prop("extra")),
            superType = TypeRef.Named("Base"),
        )

        val result = InheritanceFlattener.flatten(listOf(model("p", base, child)))

        val flat = result.complexType("p", "Child")
        flat.properties.map { it.schemaName } shouldBe listOf("id", "code", "extra")
        flat.properties[1].type shouldBe TypeRef.Primitive(PrimitiveType.INT)
    }

    @Test
    fun `child contentProperty wins over inherited one`() {
        val base = complex("Base", emptyList(), contentProperty = prop("value"))
        val ownContent = prop("value", TypeRef.Primitive(PrimitiveType.DECIMAL))
        val child = complex("Child", emptyList(), superType = TypeRef.Named("Base"), contentProperty = ownContent)

        val result = InheritanceFlattener.flatten(listOf(model("p", base, child)))

        result.complexType("p", "Child").contentProperty shouldBe ownContent
    }

    @Test
    fun `inheritance cycle terminates and every type keeps its own properties`() {
        val a = complex("A", listOf(prop("a")), superType = TypeRef.Named("B"))
        val b = complex("B", listOf(prop("b")), superType = TypeRef.Named("A"))

        // Cyclic derivation is illegal XSD; the guarantee here is termination
        // and that no type loses its own declarations.
        val result = InheritanceFlattener.flatten(listOf(model("p", a, b)))

        result.complexType("p", "A").properties.map { it.schemaName } shouldContain "a"
        result.complexType("p", "B").properties.map { it.schemaName } shouldContain "b"
    }

    @Test
    fun `unresolvable base leaves the type untouched`() {
        val child = complex("Child", listOf(prop("own")), superType = TypeRef.Named("Missing"))

        val result = InheritanceFlattener.flatten(listOf(model("p", child)))

        result.complexType("p", "Child").properties.map { it.schemaName } shouldBe listOf("own")
    }
}
