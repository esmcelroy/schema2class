package io.github.schema2class.codegen.kotlin

import io.github.schema2class.core.ir.EnumValue
import io.github.schema2class.core.ir.PrimitiveType
import io.github.schema2class.core.ir.PropertyDefinition
import io.github.schema2class.core.ir.SchemaModel
import io.github.schema2class.core.ir.SourceFormat
import io.github.schema2class.core.ir.TypeDefinition
import io.github.schema2class.core.ir.TypeRef
import io.github.schema2class.core.ir.UnionVariant
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class KotlinCodegenTest {

    private val pkg = "io.github.example"
    private val codegen = KotlinCodegen()

    private fun model(vararg types: TypeDefinition) = SchemaModel(
        namespace = null,
        packageName = pkg,
        types = types.toList(),
        sourceFormat = SourceFormat.JSON_SCHEMA,
    )

    private fun generate(vararg types: TypeDefinition): Map<String, String> =
        codegen.generate(model(*types))

    private fun sourceFor(types: Map<String, String>, name: String): String {
        val key = types.keys.first { it.endsWith("/$name.kt") }
        return types.getValue(key)
    }

    // -----------------------------------------------------------------------
    // 1. ComplexType with required and optional properties
    // -----------------------------------------------------------------------
    @Test
    fun `ComplexType generates data class with nullability and default null`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "Address",
            kotlinName = "Address",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "street",
                    kotlinName = "street",
                    type = TypeRef.Primitive(PrimitiveType.STRING),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
                PropertyDefinition(
                    schemaName = "city",
                    kotlinName = "city",
                    type = TypeRef.Primitive(PrimitiveType.STRING),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
                PropertyDefinition(
                    schemaName = "country",
                    kotlinName = "country",
                    type = TypeRef.Primitive(PrimitiveType.STRING),
                    nullable = true,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )

        val sources = generate(type)
        val source = sourceFor(sources, "Address")

        source shouldContain "data class Address("
        source shouldContain "val street: String"
        source shouldContain "val city: String"
        source shouldContain "val country: String? = null"
        // non-nullable fields have no default
        source shouldNotContain "val street: String? ="
    }

    // -----------------------------------------------------------------------
    // 2. ComplexType with a List<T> property
    // -----------------------------------------------------------------------
    @Test
    fun `ComplexType with List property generates correct type`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "Order",
            kotlinName = "Order",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "items",
                    kotlinName = "items",
                    type = TypeRef.ListOf(TypeRef.Named("Item")),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )

        val sources = generate(type)
        val source = sourceFor(sources, "Order")

        source shouldContain "data class Order("
        source shouldContain "List<Item>"
    }

    // -----------------------------------------------------------------------
    // 3. EnumType → enum class; when serialized value differs, comment present
    // -----------------------------------------------------------------------
    @Test
    fun `EnumType generates enum class with constant names`() {
        val type = TypeDefinition.EnumType(
            schemaName = "Status",
            kotlinName = "Status",
            documentation = null,
            values = listOf(
                EnumValue(serializedValue = "ACTIVE", kotlinName = "ACTIVE", documentation = null),
                EnumValue(serializedValue = "INACTIVE", kotlinName = "INACTIVE", documentation = null),
            ),
        )

        val sources = generate(type)
        val source = sourceFor(sources, "Status")

        source shouldContain "enum class Status"
        source shouldContain "ACTIVE"
        source shouldContain "INACTIVE"
        // No comments needed when serialized == kotlinName
        source shouldNotContain "// \"ACTIVE\""
    }

    @Test
    fun `EnumType with differing serialized values emits inline comments`() {
        val type = TypeDefinition.EnumType(
            schemaName = "ActionCode",
            kotlinName = "ActionCode",
            documentation = null,
            values = listOf(
                EnumValue(serializedValue = "1", kotlinName = "ADDED", documentation = null),
                EnumValue(serializedValue = "2", kotlinName = "DELETED", documentation = null),
            ),
        )

        val sources = generate(type)
        val source = sourceFor(sources, "ActionCode")

        source shouldContain "enum class ActionCode"
        source shouldContain "ADDED"
        source shouldContain "DELETED"
        source shouldContain "// \"1\""
        source shouldContain "// \"2\""
    }

    // -----------------------------------------------------------------------
    // 4. UnionType → sealed class with inner data class subtypes
    // -----------------------------------------------------------------------
    @Test
    fun `UnionType generates sealed class with inner data class variants`() {
        val type = TypeDefinition.UnionType(
            schemaName = "CancelInProgress",
            kotlinName = "CancelInProgress",
            documentation = null,
            variants = listOf(
                UnionVariant(
                    kotlinName = "BooleanVariant",
                    type = TypeRef.Primitive(PrimitiveType.BOOLEAN),
                ),
                UnionVariant(
                    kotlinName = "StringVariant",
                    type = TypeRef.Primitive(PrimitiveType.STRING),
                ),
            ),
        )

        val sources = generate(type)
        val source = sourceFor(sources, "CancelInProgress")

        source shouldContain "sealed class CancelInProgress"
        source shouldContain "data class BooleanVariant"
        // KotlinPoet escapes `value` because it is a contextual keyword in Kotlin 1.9
        source shouldContain "Boolean"
        source shouldContain "data class StringVariant"
        source shouldContain "String"
        source shouldContain ": CancelInProgress()"
    }

    // -----------------------------------------------------------------------
    // 5. AliasType → typealias
    // -----------------------------------------------------------------------
    @Test
    fun `AliasType generates typealias`() {
        val type = TypeDefinition.AliasType(
            schemaName = "EmailAddress",
            kotlinName = "EmailAddress",
            documentation = null,
            aliasedType = TypeRef.Primitive(PrimitiveType.STRING),
        )

        val sources = generate(type)
        val source = sourceFor(sources, "EmailAddress")

        source shouldContain "typealias EmailAddress = String"
    }

    // -----------------------------------------------------------------------
    // 6. TypeRef.Primitive(DECIMAL) → java.math.BigDecimal
    // -----------------------------------------------------------------------
    @Test
    fun `DECIMAL primitive emits java_math_BigDecimal`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "Price",
            kotlinName = "Price",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "amount",
                    kotlinName = "amount",
                    type = TypeRef.Primitive(PrimitiveType.DECIMAL),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )

        val sources = generate(type)
        val source = sourceFor(sources, "Price")

        source shouldContain "BigDecimal"
        // KotlinPoet may add an import or use the fully-qualified name
        val hasFqn = source.contains("java.math.BigDecimal")
        val hasImport = source.contains("import java.math.BigDecimal")
        assert(hasFqn || hasImport) {
            "Expected source to reference java.math.BigDecimal fully-qualified or via import, got:\n$source"
        }
    }

    // -----------------------------------------------------------------------
    // 7. KDoc from documentation field appears in generated output
    // -----------------------------------------------------------------------
    @Test
    fun `documentation field emits KDoc comment`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "Person",
            kotlinName = "Person",
            documentation = "Represents a person entity.",
            properties = listOf(
                PropertyDefinition(
                    schemaName = "name",
                    kotlinName = "name",
                    type = TypeRef.Primitive(PrimitiveType.STRING),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )

        val sources = generate(type)
        val source = sourceFor(sources, "Person")

        source shouldContain "Represents a person entity."
        // KDoc should be formatted as /** ... */
        source shouldContain "/**"
    }

    @Test
    fun `documentation on AliasType emits KDoc comment`() {
        val type = TypeDefinition.AliasType(
            schemaName = "EmailAddress",
            kotlinName = "EmailAddress",
            documentation = "A valid email address string.",
            aliasedType = TypeRef.Primitive(PrimitiveType.STRING),
        )

        val sources = generate(type)
        val source = sourceFor(sources, "EmailAddress")

        source shouldContain "typealias EmailAddress = String"
        source shouldContain "A valid email address string."
        source shouldContain "/**"
    }

    // -----------------------------------------------------------------------
    // 8. ComplexType with contentProperty → first constructor param
    // -----------------------------------------------------------------------
    @Test
    fun `ComplexType with contentProperty emits it as first constructor parameter`() {
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
            contentProperty = PropertyDefinition(
                schemaName = "value",
                kotlinName = "value",
                type = TypeRef.Primitive(PrimitiveType.DECIMAL),
                nullable = false,
                defaultValue = null,
                documentation = null,
            ),
        )

        val sources = generate(type)
        val source = sourceFor(sources, "AmountType")

        source shouldContain "data class AmountType("
        // KotlinPoet backtick-escapes `value` because it is a soft keyword in Kotlin 1.9
        source shouldContain "BigDecimal"
        source shouldContain "val currencyID: String? = null"
        val valueIdx = source.indexOf("BigDecimal")
        val currencyIdx = source.indexOf("currencyID")
        assert(valueIdx < currencyIdx) { "contentProperty should precede regular properties in constructor" }
    }

    // -----------------------------------------------------------------------
    // 9. New PrimitiveType values: FLOAT, BYTES, URI
    // -----------------------------------------------------------------------
    @Test
    fun `FLOAT primitive emits Float`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "Measurement",
            kotlinName = "Measurement",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "width",
                    kotlinName = "width",
                    type = TypeRef.Primitive(PrimitiveType.FLOAT),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )
        val source = sourceFor(generate(type), "Measurement")
        source shouldContain "val width: Float"
    }

    @Test
    fun `BYTES primitive emits ByteArray`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "Attachment",
            kotlinName = "Attachment",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "payload",
                    kotlinName = "payload",
                    type = TypeRef.Primitive(PrimitiveType.BYTES),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )
        val source = sourceFor(generate(type), "Attachment")
        source shouldContain "val payload: ByteArray"
    }

    @Test
    fun `URI primitive emits String`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "Link",
            kotlinName = "Link",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "href",
                    kotlinName = "href",
                    type = TypeRef.Primitive(PrimitiveType.URI),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )
        val source = sourceFor(generate(type), "Link")
        source shouldContain "val href: String"
    }

    // -----------------------------------------------------------------------
    // 10. ComplexType with no properties → plain class, not data class
    // -----------------------------------------------------------------------
    @Test
    fun `ComplexType with no properties generates plain class without data modifier`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "Placeholder",
            kotlinName = "Placeholder",
            documentation = null,
            properties = emptyList(),
        )

        val source = sourceFor(generate(type), "Placeholder")
        source shouldContain "class Placeholder"
        source shouldNotContain "data class"
        source shouldNotContain "Placeholder()"
    }

    // -----------------------------------------------------------------------
    // 10b. MapOf → Map<K, V>
    // -----------------------------------------------------------------------
    @Test
    fun `MapOf emits Map with key and value types`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "Blueprint",
            kotlinName = "Blueprint",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "dataTypes",
                    kotlinName = "dataTypes",
                    type = TypeRef.MapOf(value = TypeRef.Named("DataType")),
                    nullable = true,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )

        val source = sourceFor(generate(type), "Blueprint")
        source shouldContain "Map<String, DataType>?"
    }

    @Test
    fun `MapOf with contextual value type annotates the value argument`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "Prices",
            kotlinName = "Prices",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "bySku",
                    kotlinName = "bySku",
                    type = TypeRef.MapOf(value = TypeRef.Primitive(PrimitiveType.DECIMAL)),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )

        val source = sourceFor(kotlinxCodegen.generate(model(type)), "Prices")
        source shouldContain "Map<String, @Contextual BigDecimal>"
    }

    // -----------------------------------------------------------------------
    // 11. kotlinx.serialization annotation mode
    // -----------------------------------------------------------------------
    private val kotlinxCodegen =
        KotlinCodegen(KotlinCodegen.Options(annotationMode = AnnotationMode.KOTLINX_SERIALIZATION))

    @Test
    fun `kotlinx mode annotates data class and renamed properties`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "work-flow",
            kotlinName = "WorkFlow",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "cancel-in-progress",
                    kotlinName = "cancelInProgress",
                    type = TypeRef.Primitive(PrimitiveType.BOOLEAN),
                    nullable = true,
                    defaultValue = null,
                    documentation = null,
                ),
                PropertyDefinition(
                    schemaName = "name",
                    kotlinName = "name",
                    type = TypeRef.Primitive(PrimitiveType.STRING),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )

        val source = sourceFor(kotlinxCodegen.generate(model(type)), "WorkFlow")

        source shouldContain "@Serializable"
        source shouldContain "@SerialName(\"work-flow\")"
        source shouldContain "@SerialName(\"cancel-in-progress\")"
        // Names that already match need no @SerialName
        source shouldNotContain "@SerialName(\"name\")"
        source shouldContain "import kotlinx.serialization.Serializable"
    }

    @Test
    fun `kotlinx mode adds Contextual to java math and time types`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "Price",
            kotlinName = "Price",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "amount",
                    kotlinName = "amount",
                    type = TypeRef.Primitive(PrimitiveType.DECIMAL),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
                PropertyDefinition(
                    schemaName = "history",
                    kotlinName = "history",
                    type = TypeRef.ListOf(TypeRef.Primitive(PrimitiveType.DATE)),
                    nullable = true,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )

        val source = sourceFor(kotlinxCodegen.generate(model(type)), "Price")

        source shouldContain "@Contextual"
        source shouldContain "BigDecimal"
        // List element type is annotated, not the list itself
        source shouldContain "List<@Contextual LocalDate>"
    }

    @Test
    fun `kotlinx mode annotates enum constants with differing serialized values`() {
        val type = TypeDefinition.EnumType(
            schemaName = "ActionCode",
            kotlinName = "ActionCode",
            documentation = null,
            values = listOf(
                EnumValue(serializedValue = "1", kotlinName = "ADDED", documentation = null),
                EnumValue(serializedValue = "ACTIVE", kotlinName = "ACTIVE", documentation = null),
            ),
        )

        val source = sourceFor(kotlinxCodegen.generate(model(type)), "ActionCode")

        source shouldContain "@Serializable"
        source shouldContain "@SerialName(\"1\")"
        source shouldContain "ADDED"
        // Matching constant stays unannotated
        source shouldNotContain "@SerialName(\"ACTIVE\")"
    }

    @Test
    fun `kotlinx mode annotates sealed class and variants`() {
        val type = TypeDefinition.UnionType(
            schemaName = "Threshold",
            kotlinName = "Threshold",
            documentation = null,
            variants = listOf(
                UnionVariant(kotlinName = "DoubleVariant", type = TypeRef.Primitive(PrimitiveType.DOUBLE)),
                UnionVariant(kotlinName = "StringVariant", type = TypeRef.Primitive(PrimitiveType.STRING)),
            ),
        )

        val source = sourceFor(kotlinxCodegen.generate(model(type)), "Threshold")

        // Sealed parent and both variants annotated
        source.split("@Serializable").size shouldBe 4
    }

    // -----------------------------------------------------------------------
    // 12. xmlutil annotation mode
    // -----------------------------------------------------------------------
    @Test
    fun `xmlutil mode annotates kinds and type xml name with namespace`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "AmountType",
            kotlinName = "AmountType",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "currencyID",
                    kotlinName = "currencyId",
                    type = TypeRef.Primitive(PrimitiveType.STRING),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                    kind = io.github.schema2class.core.ir.PropertyKind.ATTRIBUTE,
                ),
            ),
            contentProperty = PropertyDefinition(
                schemaName = "value",
                kotlinName = "value",
                type = TypeRef.Primitive(PrimitiveType.DECIMAL),
                nullable = false,
                defaultValue = null,
                documentation = null,
                kind = io.github.schema2class.core.ir.PropertyKind.CONTENT,
            ),
        )
        val xmlCodegen = KotlinCodegen(KotlinCodegen.Options(annotationMode = AnnotationMode.XMLUTIL))
        val xmlModel = SchemaModel(
            namespace = "urn:test:business-doc",
            packageName = pkg,
            types = listOf(type),
            sourceFormat = SourceFormat.XSD,
        )

        val source = sourceFor(xmlCodegen.generate(xmlModel), "AmountType")

        // kotlinx annotations still present (XMLUTIL is a superset)
        source shouldContain "@Serializable"
        source shouldContain "@SerialName(\"currencyID\")"
        // xmlutil additions
        source shouldContain "@XmlSerialName("
        source shouldContain "value = \"AmountType\""
        source shouldContain "namespace = \"urn:test:business-doc\""
        source shouldContain "@XmlValue"
        source shouldContain "@XmlElement(false)"
        source shouldContain "@Serializable(with = Schema2ClassBigDecimalAsStringSerializer::class)"
        source shouldContain "object Schema2ClassBigDecimalAsStringSerializer : KSerializer<BigDecimal>"
        source shouldNotContain "@Contextual BigDecimal"
    }

    @Test
    fun `xmlutil mode marks element-kind properties as XmlElement true`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "Order",
            kotlinName = "Order",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "item",
                    kotlinName = "item",
                    type = TypeRef.ListOf(TypeRef.Named("Item")),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )
        val xmlCodegen = KotlinCodegen(KotlinCodegen.Options(annotationMode = AnnotationMode.XMLUTIL))

        val source = sourceFor(xmlCodegen.generate(model(type)), "Order")

        source shouldContain "@XmlElement(true)"
    }

    @Test
    fun `kotlinx mode emits JsonClassDiscriminator for unions with a discriminator`() {
        val type = TypeDefinition.UnionType(
            schemaName = "Pet",
            kotlinName = "Pet",
            documentation = null,
            variants = listOf(
                UnionVariant(kotlinName = "CatVariant", type = TypeRef.Named("Cat")),
                UnionVariant(kotlinName = "DogVariant", type = TypeRef.Named("Dog")),
            ),
            discriminatorProperty = "petType",
        )

        val source = sourceFor(kotlinxCodegen.generate(model(type)), "Pet")

        source shouldContain "@JsonClassDiscriminator(\"petType\")"
        source shouldContain "@OptIn(ExperimentalSerializationApi::class)"

        // Without a discriminator, neither annotation appears
        val plain = sourceFor(
            kotlinxCodegen.generate(model(type.copy(discriminatorProperty = null))),
            "Pet",
        )
        plain shouldNotContain "JsonClassDiscriminator"
    }

    @Test
    fun `default mode emits no serialization annotations`() {
        val type = TypeDefinition.ComplexType(
            schemaName = "plain-type",
            kotlinName = "PlainType",
            documentation = null,
            properties = listOf(
                PropertyDefinition(
                    schemaName = "amount",
                    kotlinName = "amount",
                    type = TypeRef.Primitive(PrimitiveType.DECIMAL),
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                ),
            ),
        )

        val source = sourceFor(generate(type), "PlainType")

        source shouldNotContain "@Serializable"
        source shouldNotContain "@SerialName"
        source shouldNotContain "@Contextual"
    }

    // -----------------------------------------------------------------------
    // Additional: file path is correctly derived from package and type name
    // -----------------------------------------------------------------------
    @Test
    fun `file path mirrors package and type name`() {
        val type = TypeDefinition.AliasType(
            schemaName = "Foo",
            kotlinName = "Foo",
            documentation = null,
            aliasedType = TypeRef.Primitive(PrimitiveType.INT),
        )

        val sources = generate(type)
        assert(sources.containsKey("io/github/example/Foo.kt")) {
            "Expected key 'io/github/example/Foo.kt' but got keys: ${sources.keys}"
        }
    }
}
