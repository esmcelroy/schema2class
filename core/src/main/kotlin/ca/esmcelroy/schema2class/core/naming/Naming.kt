package ca.esmcelroy.schema2class.core.naming

import java.io.File

enum class NamingTarget {
    TYPE,
    PROPERTY,
}

data class NamingContext(
    val target: NamingTarget,
    val schemaName: String,
    val ownerSchemaName: String? = null,
    val ownerKotlinName: String? = null,
    val title: String? = null,
    val itemTitle: String? = null,
    val isArray: Boolean = false,
)

fun interface NamingStrategy {
    fun nameFor(context: NamingContext): String?
}

data class NamingBindings(
    val typeNames: Map<String, String> = emptyMap(),
    val propertyNames: Map<String, String> = emptyMap(),
) {
    fun typeName(schemaName: String): String? = typeNames[schemaName]

    fun propertyName(ownerSchemaName: String, ownerKotlinName: String, schemaName: String): String? =
        propertyNames["$ownerKotlinName.$schemaName"]
            ?: propertyNames["$ownerSchemaName.$schemaName"]
            ?: propertyNames[schemaName]

    companion object {
        val EMPTY = NamingBindings()

        fun fromFile(file: File): NamingBindings =
            fromLines(file.readLines())

        fun fromLines(lines: Iterable<String>): NamingBindings {
            val typeNames = linkedMapOf<String, String>()
            val propertyNames = linkedMapOf<String, String>()

            lines.forEachIndexed { index, rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed

                val parts = line.split("=", limit = 2)
                require(parts.size == 2) {
                    "Invalid naming binding at line ${index + 1}: expected KEY = NAME"
                }
                val key = parts[0].trim()
                val value = parts[1].trim()
                require(key.isNotEmpty() && value.isNotEmpty()) {
                    "Invalid naming binding at line ${index + 1}: key and name must be non-empty"
                }

                if ('.' in key) {
                    propertyNames[key] = value
                } else {
                    typeNames[key] = value
                }
            }

            return NamingBindings(typeNames, propertyNames)
        }
    }
}
