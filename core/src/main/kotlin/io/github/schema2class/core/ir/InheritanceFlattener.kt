package io.github.schema2class.core.ir

/**
 * Resolves [TypeDefinition.ComplexType.superType] chains by flattening inherited
 * properties into each subtype, across one or more models (cross-namespace
 * extension is common — e.g. UN/CEFACT qualified types extending unqualified ones).
 *
 * Kotlin data classes are final, so schema inheritance cannot map to Kotlin
 * inheritance; flattening is the deliberate mapping:
 *  - inherited properties come first (XSD extension appends child particles
 *    after the parent's),
 *  - a child re-declaration of the same schemaName wins, in the parent's position
 *    (XSD restriction semantics),
 *  - [TypeDefinition.ComplexType.contentProperty] is inherited when the child has
 *    none (simpleContent extension/restriction of another simpleContent type).
 *
 * [TypeDefinition.ComplexType.superType] is kept afterwards as provenance; codegen
 * ignores it for class hierarchy purposes.
 *
 * Unresolvable bases (no matching type in any model) and inheritance cycles leave
 * the type's own declaration untouched.
 */
object InheritanceFlattener {

    fun flatten(models: List<SchemaModel>): List<SchemaModel> {
        val index = mutableMapOf<Pair<String, String>, TypeDefinition.ComplexType>()
        val homePackage = mutableMapOf<TypeDefinition.ComplexType, String>()
        for (model in models) {
            for (type in model.types) {
                if (type is TypeDefinition.ComplexType) {
                    index[model.packageName to type.kotlinName] = type
                    homePackage[type] = model.packageName
                }
            }
        }

        val memo = mutableMapOf<TypeDefinition.ComplexType, TypeDefinition.ComplexType>()

        fun resolveBase(type: TypeDefinition.ComplexType): TypeDefinition.ComplexType? {
            val ref = type.superType as? TypeRef.Named ?: return null
            val pkg = ref.packageName ?: homePackage.getValue(type)
            return index[pkg to ref.name]
        }

        fun flattenType(
            type: TypeDefinition.ComplexType,
            visiting: MutableSet<TypeDefinition.ComplexType>,
        ): TypeDefinition.ComplexType {
            memo[type]?.let { return it }
            val base = resolveBase(type)
            if (base == null || !visiting.add(type)) {
                memo[type] = type
                return type
            }
            val flatBase = flattenType(base, visiting)
            visiting.remove(type)

            val childByName = type.properties.associateBy { it.schemaName }
            val inheritedNames = flatBase.properties.map { it.schemaName }.toSet()
            val merged = flatBase.properties.map { childByName[it.schemaName] ?: it } +
                type.properties.filter { it.schemaName !in inheritedNames }

            val result = type.copy(
                properties = merged,
                contentProperty = type.contentProperty ?: flatBase.contentProperty,
            )
            memo[type] = result
            return result
        }

        return models.map { model ->
            model.copy(
                types = model.types.map { type ->
                    if (type is TypeDefinition.ComplexType) flattenType(type, mutableSetOf()) else type
                },
            )
        }
    }
}
