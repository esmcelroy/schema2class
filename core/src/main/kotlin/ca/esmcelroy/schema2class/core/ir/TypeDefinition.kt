package ca.esmcelroy.schema2class.core.ir

/**
 * A named type in the IR. Every definition has both the original schema name and a
 * sanitized Kotlin identifier. Anonymous schema types (inline XSD complexType, unnamed
 * JSON Schema objects) are assigned a generated [schemaName] by the parser.
 */
sealed class TypeDefinition {
    abstract val schemaName: String
    abstract val kotlinName: String
    abstract val documentation: String?

    /**
     * A structured type with named properties. Maps to a Kotlin data class.
     * [superType] is non-null when the type extends another (xs:extension).
     * [contentProperty] is non-null for xs:simpleContent types (complexType with a text body
     * plus attributes). Codegen emits it as the first constructor parameter, conventionally
     * named "value", before the attribute properties.
     */
    data class ComplexType(
        override val schemaName: String,
        override val kotlinName: String,
        override val documentation: String?,
        val properties: List<PropertyDefinition>,
        val superType: TypeRef? = null,
        val contentProperty: PropertyDefinition? = null,
    ) : TypeDefinition()

    /**
     * A closed set of named values. Maps to a Kotlin enum class.
     * [EnumValue.serializedValue] is the wire value; [EnumValue.kotlinName] is the
     * enum constant. These differ when schema values are not valid Kotlin identifiers
     * (e.g. UNECE numeric codes like "1", "2", or hyphenated values).
     */
    data class EnumType(
        override val schemaName: String,
        override val kotlinName: String,
        override val documentation: String?,
        val values: List<EnumValue>,
        val baseType: PrimitiveType = PrimitiveType.STRING,
    ) : TypeDefinition()

    /**
     * A union of two or more types (JSON Schema oneOf/anyOf, XSD xs:choice).
     * Maps to a Kotlin sealed class where each [UnionVariant] becomes a data class subtype
     * wrapping a single value of the variant's type.
     * [discriminatorProperty] carries the OpenAPI-style discriminator propertyName when
     * present, for serialization annotation modes (@JsonClassDiscriminator).
     */
    data class UnionType(
        override val schemaName: String,
        override val kotlinName: String,
        override val documentation: String?,
        val variants: List<UnionVariant>,
        val discriminatorProperty: String? = null,
    ) : TypeDefinition()

    /**
     * A constrained or aliased simple type (XSD simpleType restriction, JSON Schema
     * string/number with format or constraint keywords). Maps to a type alias or,
     * when the value-class codegen option is enabled, a @JvmInline value class.
     */
    data class AliasType(
        override val schemaName: String,
        override val kotlinName: String,
        override val documentation: String?,
        val aliasedType: TypeRef,
        val constraints: List<Constraint> = emptyList(),
    ) : TypeDefinition()
}

data class EnumValue(
    /** The value as it appears in the schema (the wire representation). */
    val serializedValue: String,
    /** A valid Kotlin enum constant name derived from the serialized value or schema annotation. */
    val kotlinName: String,
    val documentation: String?,
)

data class UnionVariant(
    /** Name for the generated wrapper data class (e.g. "BooleanVariant", "ExpressionSyntaxVariant"). */
    val kotlinName: String,
    val type: TypeRef,
)
