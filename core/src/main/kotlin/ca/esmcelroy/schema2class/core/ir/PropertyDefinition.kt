package ca.esmcelroy.schema2class.core.ir

/**
 * A single property on a [TypeDefinition.ComplexType].
 *
 * [schemaName] is the original name from the schema and becomes the value for
 * @SerialName/@JsonProperty when it differs from [kotlinName].
 * [type] is [TypeRef.ListOf] when the property is multi-valued.
 * [nullable] is true when the property may be absent (XSD minOccurs=0, JSON Schema
 * property not in the required array).
 */
data class PropertyDefinition(
    val schemaName: String,
    val kotlinName: String,
    val type: TypeRef,
    val nullable: Boolean,
    val defaultValue: String?,
    val documentation: String?,
    val constraints: List<Constraint> = emptyList(),
    val kind: PropertyKind = PropertyKind.ELEMENT,
)

/**
 * How a property is carried on the wire — the distinction XML serialization
 * annotations need (@XmlElement vs attribute vs @XmlValue). JSON Schema
 * properties are always [ELEMENT].
 */
enum class PropertyKind {
    /** An xs:element child or a JSON object member. */
    ELEMENT,

    /** An xs:attribute. */
    ATTRIBUTE,

    /** The typed text body of an xs:simpleContent type ([TypeDefinition.ComplexType.contentProperty]). */
    CONTENT,
}

/** Validation constraints carried from the schema into the IR. */
sealed class Constraint {
    data class ExactLength(val value: Int) : Constraint()
    data class MinLength(val value: Int) : Constraint()
    data class MaxLength(val value: Int) : Constraint()
    data class Pattern(val regex: String) : Constraint()
    data class MinValue(val value: String) : Constraint()
    data class MaxValue(val value: String) : Constraint()
    data class TotalDigits(val value: Int) : Constraint()
    data class FractionDigits(val value: Int) : Constraint()
    data class MinItems(val value: Int) : Constraint()
    data class MaxItems(val value: Int) : Constraint()
}
