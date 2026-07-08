package ca.esmcelroy.schema2class.core.ir

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class SchemaModelTest {

    // Mirrors the simple shiporder XSD sample (sequence, optional element, attribute, list).
    @Test
    fun `ComplexType with list and nullable properties round-trips through IR`() {
        val model = SchemaModel(
            namespace = null,
            packageName = "ca.esmcelroy.schema2class.sample",
            sourceFormat = SourceFormat.XSD,
            types = listOf(
                TypeDefinition.ComplexType(
                    schemaName = "shiporder",
                    kotlinName = "Shiporder",
                    documentation = null,
                    properties = listOf(
                        PropertyDefinition(
                            schemaName = "orderperson",
                            kotlinName = "orderperson",
                            type = TypeRef.Primitive(PrimitiveType.STRING),
                            nullable = false,
                            defaultValue = null,
                            documentation = null,
                        ),
                        PropertyDefinition(
                            schemaName = "item",
                            kotlinName = "item",
                            type = TypeRef.ListOf(TypeRef.Named("ShiporderItem")),
                            nullable = false,
                            defaultValue = null,
                            documentation = null,
                        ),
                    ),
                ),
            ),
        )

        val type = model.types.single().shouldBeInstanceOf<TypeDefinition.ComplexType>()
        type.properties shouldHaveSize 2
        val listProp = type.properties[1]
        withClue("list property should use TypeRef.ListOf") {
            listProp.type.shouldBeInstanceOf<TypeRef.ListOf>()
        }
        (listProp.type as TypeRef.ListOf).element shouldBe TypeRef.Named("ShiporderItem")
    }

    // Mirrors UNECE ActionCode: numeric serialized values, human-readable kotlin names.
    @Test
    fun `EnumType with non-identifier serialized values round-trips through IR`() {
        val model = SchemaModel(
            namespace = "urn:un:unece:uncefact:codelist:standard:UNECE:ActionCode:D24A",
            packageName = "ca.esmcelroy.schema2class.uncefact.codelist",
            sourceFormat = SourceFormat.XSD,
            types = listOf(
                TypeDefinition.EnumType(
                    schemaName = "ActionCodeContentType",
                    kotlinName = "ActionCodeContentType",
                    documentation = null,
                    values = listOf(
                        EnumValue(serializedValue = "1", kotlinName = "ADDED", documentation = "The information is to be or has been added."),
                        EnumValue(serializedValue = "2", kotlinName = "DELETED", documentation = "The information is to be or has been deleted."),
                        EnumValue(serializedValue = "100", kotlinName = "FINAL_RESPONSE", documentation = "The response is a final one."),
                    ),
                ),
            ),
        )

        val enum = model.types.single().shouldBeInstanceOf<TypeDefinition.EnumType>()
        enum.values shouldHaveSize 3
        withClue("serialized value must survive round-trip unchanged") {
            enum.values[0].serializedValue shouldBe "1"
            enum.values[0].kotlinName shouldBe "ADDED"
        }
    }

    // Mirrors GitHub Workflow cancel-in-progress: oneOf [boolean, $ref expressionSyntax].
    @Test
    fun `UnionType with primitive and named variant round-trips through IR`() {
        val model = SchemaModel(
            namespace = "https://json.schemastore.org/github-workflow.json",
            packageName = "ca.esmcelroy.schema2class.github",
            sourceFormat = SourceFormat.JSON_SCHEMA,
            types = listOf(
                TypeDefinition.UnionType(
                    schemaName = "cancelInProgress",
                    kotlinName = "CancelInProgress",
                    documentation = null,
                    variants = listOf(
                        UnionVariant(kotlinName = "BooleanVariant", type = TypeRef.Primitive(PrimitiveType.BOOLEAN)),
                        UnionVariant(kotlinName = "ExpressionSyntaxVariant", type = TypeRef.Named("ExpressionSyntax")),
                    ),
                ),
            ),
        )

        val union = model.types.single().shouldBeInstanceOf<TypeDefinition.UnionType>()
        union.variants shouldHaveSize 2
        union.variants[0].type shouldBe TypeRef.Primitive(PrimitiveType.BOOLEAN)
        union.variants[1].type shouldBe TypeRef.Named("ExpressionSyntax")
    }

    // Verifies TypeRef.Named can express a cross-package reference (multi-file schemas).
    @Test
    fun `cross-package TypeRef Named carries packageName`() {
        val ref = TypeRef.Named(name = "CoreComponentType", packageName = "ca.esmcelroy.schema2class.uncefact.data")
        ref.name shouldBe "CoreComponentType"
        ref.packageName shouldBe "ca.esmcelroy.schema2class.uncefact.data"
    }

    // Mirrors UNECE AmountType: xs:simpleContent + xs:extension → typed body value + attributes.
    @Test
    fun `ComplexType with contentProperty round-trips through IR`() {
        val contentProp = PropertyDefinition(
            schemaName = "value",
            kotlinName = "value",
            type = TypeRef.Primitive(PrimitiveType.DECIMAL),
            nullable = false,
            defaultValue = null,
            documentation = null,
        )
        val type = TypeDefinition.ComplexType(
            schemaName = "AmountType",
            kotlinName = "AmountType",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "currencyID",
                    kotlinName = "currencyID",
                    type = TypeRef.Primitive(PrimitiveType.STRING),
                    nullable = true,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
            contentProperty = contentProp,
        )

        type.contentProperty shouldBe contentProp
        type.properties shouldHaveSize 1
        type.properties[0].schemaName shouldBe "currencyID"
    }

    // Verifies new PrimitiveType values (BYTES, URI, FLOAT) are accessible.
    @Test
    fun `new PrimitiveType values have correct kotlinType strings`() {
        PrimitiveType.BYTES.kotlinType shouldBe "ByteArray"
        PrimitiveType.URI.kotlinType shouldBe "String"
        PrimitiveType.FLOAT.kotlinType shouldBe "Float"
    }
}
