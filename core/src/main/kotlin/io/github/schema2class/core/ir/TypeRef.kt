package io.github.schema2class.core.ir

/**
 * A reference to a type within the IR. Parsers produce TypeRefs; codegen resolves them
 * to Kotlin type names.
 *
 * Nullability is NOT encoded here — it lives on [PropertyDefinition.nullable] so that
 * the same type can be used in both nullable and non-null contexts without duplication.
 */
sealed class TypeRef {
    /** Reference to a named type defined in a [SchemaModel]. */
    data class Named(
        val name: String,
        /**
         * Package of the referenced type. Null means "same package as the referencing model".
         * Non-null means a cross-file/cross-namespace reference.
         */
        val packageName: String? = null,
    ) : TypeRef()

    /** A primitive/built-in type with no schema definition. */
    data class Primitive(val type: PrimitiveType) : TypeRef()

    /**
     * An ordered collection of zero or more elements of [element] type.
     * XSD maxOccurs > 1 and JSON Schema "type": "array" both produce this.
     */
    data class ListOf(val element: TypeRef) : TypeRef()
}

/**
 * The set of built-in primitive types the IR recognizes.
 * [kotlinType] is the fully-qualified Kotlin/Java type name used by codegen.
 */
enum class PrimitiveType(val kotlinType: String) {
    STRING("String"),
    INT("Int"),
    LONG("Long"),
    DOUBLE("Double"),
    BOOLEAN("Boolean"),
    DECIMAL("java.math.BigDecimal"),
    DATE("java.time.LocalDate"),
    DATE_TIME("java.time.OffsetDateTime"),
    DURATION("java.time.Duration"),
    /** Escape hatch for xs:any and JSON Schema schemas with no type constraint. */
    ANY("Any"),
}
