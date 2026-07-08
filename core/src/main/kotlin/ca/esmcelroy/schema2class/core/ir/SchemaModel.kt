package ca.esmcelroy.schema2class.core.ir

/**
 * The complete parsed output of a single schema document.
 *
 * Both parsers (XSD, JSON Schema) produce a [SchemaModel]. The codegen consumes it.
 * Cross-file [TypeRef.Named] references with a non-null [TypeRef.Named.packageName]
 * point to types in other models.
 */
data class SchemaModel(
    /** Original namespace URI (XSD targetNamespace or JSON Schema $id), null if absent. */
    val namespace: String?,
    /** Resolved Kotlin package for all types in this model. */
    val packageName: String,
    val types: List<TypeDefinition>,
    val sourceFormat: SourceFormat,
)

enum class SourceFormat { XSD, JSON_SCHEMA }
