package io.github.schema2class.parser.jsonschema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.schema2class.core.ir.Constraint
import io.github.schema2class.core.ir.EnumValue
import io.github.schema2class.core.ir.PrimitiveType
import io.github.schema2class.core.ir.PropertyDefinition
import io.github.schema2class.core.ir.SchemaModel
import io.github.schema2class.core.ir.SourceFormat
import io.github.schema2class.core.ir.TypeDefinition
import io.github.schema2class.core.ir.TypeRef
import io.github.schema2class.core.ir.UnionVariant
import java.io.File
import java.io.InputStream

/**
 * Parses a JSON Schema draft-07 document and produces a [SchemaModel] IR.
 *
 * Out of scope for v1 (see TODOs inside):
 * - `allOf`, `not`, `if/then/else` combiners
 * - External `$ref` (cross-file) — emits `TypeRef.Named` with a warning
 * - JSON Schema `format` keyword — treated as plain STRING
 * - `$defs` is treated identically to `definitions`
 */
class JsonSchemaParser {

    private val mapper = ObjectMapper().registerKotlinModule()

    /** Parse JSON Schema from [input] stream; assigns types to [packageName]. */
    fun parse(input: InputStream, packageName: String): SchemaModel =
        ParseContext(packageName, mapper.readTree(input)).parse()

    /** Convenience overload for [File] inputs. */
    fun parse(file: File, packageName: String): SchemaModel =
        file.inputStream().use { parse(it, packageName) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal parse context — one instance per parse() call, not thread-safe.
// ─────────────────────────────────────────────────────────────────────────────

private class ParseContext(
    val packageName: String,
    val root: JsonNode,
) {
    val types = mutableListOf<TypeDefinition>()

    /**
     * Set of `$ref` paths currently being resolved.
     * If a ref appears here we are in a cycle; emit `TypeRef.Named` without recursing.
     */
    val resolvedRefs = mutableSetOf<String>()

    // Support both "definitions" (draft-07) and "$defs" (draft 2019-09+).
    val definitionsNode: JsonNode? = root.get("definitions") ?: root.get("\$defs")

    fun parse(): SchemaModel {
        val namespace = root.get("\$id")?.textValue()

        // Process all top-level definitions first so $ref resolution can find them.
        definitionsNode?.fields()?.forEach { (name, node) ->
            if (types.none { it.schemaName == name }) {
                processDefinition(name, node)
            }
        }

        // Process root schema if it looks like an object type.
        if (root.get("type")?.textValue() == "object" || root.has("properties")) {
            val rootName = root.get("title")?.textValue()
                ?: namespace
                    ?.substringAfterLast("/")
                    ?.removeSuffix(".json")
                    ?.removeSuffix(".schema")
                ?: "Root"
            if (types.none { it.schemaName == rootName }) {
                processDefinition(rootName, root)
            }
        }

        return SchemaModel(
            namespace = namespace,
            packageName = packageName,
            types = types,
            sourceFormat = SourceFormat.JSON_SCHEMA,
        )
    }

    // ── Definition dispatch ────────────────────────────────────────────────

    /**
     * Register a named schema node as a [TypeDefinition] in [types].
     * Idempotent: re-entering with the same [schemaName] is a no-op.
     */
    fun processDefinition(schemaName: String, node: JsonNode) {
        if (types.any { it.schemaName == schemaName }) return

        val kotlinName = toPascalCase(schemaName)
        val doc = extractDoc(node)

        when {
            // $ref-only definitions become AliasType (draft-07 ignores $ref siblings).
            node.has("\$ref") -> {
                val refPath = node.get("\$ref").textValue()
                val aliasTarget = resolveRefToTypeRef(refPath)
                types.add(TypeDefinition.AliasType(schemaName, kotlinName, doc, aliasTarget))
            }

            node.has("enum") -> {
                types.add(buildEnumType(schemaName, kotlinName, doc, node))
            }

            node.has("oneOf") || node.has("anyOf") -> {
                // Register a placeholder before recursing to break reference cycles.
                types.add(TypeDefinition.UnionType(schemaName, kotlinName, doc, emptyList()))
                val union = buildUnionType(schemaName, kotlinName, doc, node)
                replace(schemaName, union)
            }

            schemaTypeValue(node) == "object" || node.has("properties") -> {
                // Register a placeholder before recursing to break reference cycles.
                types.add(TypeDefinition.ComplexType(schemaName, kotlinName, doc, emptyList()))
                val complex = buildComplexType(schemaName, kotlinName, doc, node)
                replace(schemaName, complex)
            }

            else -> {
                // Simple / primitive type — possibly with constraints → AliasType.
                val baseRef = primitiveTypeRef(node)
                val constraints = extractConstraints(node)
                types.add(TypeDefinition.AliasType(schemaName, kotlinName, doc, baseRef, constraints))
            }
        }
    }

    /** Replace a placeholder type (by schemaName) with the fully-built version. */
    private fun replace(schemaName: String, built: TypeDefinition) {
        val idx = types.indexOfFirst { it.schemaName == schemaName }
        if (idx >= 0) types[idx] = built else types.add(built)
    }

    // ── TypeRef resolution ─────────────────────────────────────────────────

    /**
     * Produce a [TypeRef] for any schema node.
     * Creates anonymous inline types when the node is a complex/enum/union schema.
     * [suggestedName] is used to name those anonymous types.
     */
    fun resolveTypeRef(node: JsonNode, suggestedName: String): TypeRef {
        return when {
            node.has("\$ref") -> resolveRefToTypeRef(node.get("\$ref").textValue())

            node.has("enum") -> {
                if (types.none { it.schemaName == suggestedName }) {
                    processDefinition(suggestedName, node)
                }
                TypeRef.Named(toPascalCase(suggestedName))
            }

            node.has("oneOf") || node.has("anyOf") -> {
                if (types.none { it.schemaName == suggestedName }) {
                    processDefinition(suggestedName, node)
                }
                TypeRef.Named(toPascalCase(suggestedName))
            }

            schemaTypeValue(node) == "object" || node.has("properties") -> {
                if (types.none { it.schemaName == suggestedName }) {
                    processDefinition(suggestedName, node)
                }
                TypeRef.Named(toPascalCase(suggestedName))
            }

            schemaTypeValue(node) == "array" -> {
                val items = node.get("items")
                val elementType = if (items != null) {
                    resolveTypeRef(items, "${suggestedName}Item")
                } else {
                    TypeRef.Primitive(PrimitiveType.ANY)
                }
                TypeRef.ListOf(elementType)
            }

            // TODO v1: allOf, not, if/then/else — out of scope, fall through to primitive
            else -> primitiveTypeRef(node)
        }
    }

    /**
     * Follow a `$ref` string and return a [TypeRef].
     * Same-document refs (`#/definitions/Foo`, `#/$defs/Foo`) trigger definition processing.
     * External refs emit a warning and return `TypeRef.Named` with `packageName = null`.
     */
    fun resolveRefToTypeRef(refPath: String): TypeRef {
        if (!refPath.startsWith("#")) {
            // TODO v1: external $ref not supported — emit named ref with null package and warn
            val simpleName = refPath
                .substringAfterLast("/")
                .substringAfterLast("#")
                .removeSuffix(".json")
                .removeSuffix(".schema")
            System.err.println("Warning: external \$ref not supported in v1, emitting named ref: $refPath")
            return TypeRef.Named(toPascalCase(simpleName.ifEmpty { "External" }), packageName = null)
        }

        val defName = defNameFromRef(refPath)
            ?: return TypeRef.Primitive(PrimitiveType.ANY) // bare "#" pointing to root schema

        val kotlinName = toPascalCase(defName)

        if (refPath in resolvedRefs) {
            // Cycle detected — return Named without recursing further.
            return TypeRef.Named(kotlinName)
        }

        if (types.none { it.schemaName == defName }) {
            resolvedRefs.add(refPath)
            val defNode = resolveRef(refPath)
            if (defNode != null) {
                processDefinition(defName, defNode)
            }
            resolvedRefs.remove(refPath)
        }

        return TypeRef.Named(kotlinName)
    }

    /** Resolve a JSON Pointer ref to a [JsonNode] within this document. */
    fun resolveRef(refPath: String): JsonNode? {
        if (!refPath.startsWith("#")) return null
        val pointer = refPath.removePrefix("#")
        if (pointer.isEmpty()) return root
        var node: JsonNode? = root
        for (part in pointer.removePrefix("/").split("/")) {
            // JSON Pointer escape sequences
            val decoded = part.replace("~1", "/").replace("~0", "~")
            node = node?.get(decoded) ?: return null
        }
        return node
    }

    /**
     * Extract the definition name from a same-document ref.
     * Supports `#/definitions/Foo` and `#/$defs/Foo`.
     */
    fun defNameFromRef(refPath: String): String? = when {
        refPath.startsWith("#/definitions/") -> refPath.removePrefix("#/definitions/")
        refPath.startsWith("#/\$defs/") -> refPath.removePrefix("#/\$defs/")
        else -> null
    }

    // ── Type builders ──────────────────────────────────────────────────────

    fun buildComplexType(
        schemaName: String,
        kotlinName: String,
        doc: String?,
        node: JsonNode,
    ): TypeDefinition.ComplexType {
        val required = node.get("required")
            ?.map { it.textValue() }
            ?.toSet()
            ?: emptySet()

        // additionalProperties: false — parsed but no IR representation needed.
        val propsNode = node.get("properties")
            ?: return TypeDefinition.ComplexType(schemaName, kotlinName, doc, emptyList())

        val properties = propsNode.fields().asSequence().map { (propSchemaName, propNode) ->
            val propKotlinName = toCamelCase(propSchemaName)
            val nullable = propSchemaName !in required
            val propDoc = extractDoc(propNode)
            val defaultValue = propNode.get("default")?.toString()
            val constraints = extractConstraints(propNode)
            // Suggested name for anonymous inline types: ParentType_propertyName
            val suggestedTypeName = "${schemaName}_${propSchemaName}"
            val typeRef = resolveTypeRef(propNode, suggestedTypeName)

            PropertyDefinition(
                schemaName = propSchemaName,
                kotlinName = propKotlinName,
                type = typeRef,
                nullable = nullable,
                defaultValue = defaultValue,
                documentation = propDoc,
                constraints = constraints,
            )
        }.toList()

        return TypeDefinition.ComplexType(schemaName, kotlinName, doc, properties)
    }

    fun buildEnumType(
        schemaName: String,
        kotlinName: String,
        doc: String?,
        node: JsonNode,
    ): TypeDefinition.EnumType {
        val values = node.get("enum").mapIndexed { i, enumNode ->
            val serialized = enumNode.asText()
            EnumValue(
                serializedValue = serialized,
                kotlinName = toScreamingSnakeCase(serialized).ifEmpty { "VALUE_$i" },
                documentation = null,
            )
        }
        // Deduplicate Kotlin names in case of collision (e.g. "foo" and "Foo" → same constant).
        val deduped = deduplicateEnumNames(values)
        val baseType = inferEnumBaseType(node)
        return TypeDefinition.EnumType(schemaName, kotlinName, doc, deduped, baseType)
    }

    fun buildUnionType(
        schemaName: String,
        kotlinName: String,
        doc: String?,
        node: JsonNode,
    ): TypeDefinition.UnionType {
        val branchNodes = (node.get("oneOf") ?: node.get("anyOf"))!!
        val variants = branchNodes.mapIndexed { i, variantNode ->
            val variantLabel = when {
                variantNode.has("\$ref") -> {
                    val refPath = variantNode.get("\$ref").textValue()
                    defNameFromRef(refPath)?.let { toPascalCase(it) } ?: "Variant$i"
                }
                schemaTypeValue(variantNode) != null -> toPascalCase(schemaTypeValue(variantNode)!!)
                else -> "Variant$i"
            }
            val variantKotlinName = "${variantLabel}Variant"
            val typeRef = resolveTypeRef(variantNode, "${schemaName}_${variantLabel}")
            UnionVariant(kotlinName = variantKotlinName, type = typeRef)
        }
        return TypeDefinition.UnionType(schemaName, kotlinName, doc, variants)
    }

    // ── Primitive / constraint helpers ─────────────────────────────────────

    /**
     * Return the effective JSON Schema `type` string for [node].
     * When `type` is an array (e.g. `["string", "null"]`) returns the first non-"null" entry.
     */
    fun schemaTypeValue(node: JsonNode): String? {
        val typeNode = node.get("type") ?: return null
        return when {
            typeNode.isTextual -> typeNode.textValue()
            typeNode.isArray -> typeNode.mapNotNull { it.textValue() }.firstOrNull { it != "null" }
            else -> null
        }
    }

    fun primitiveTypeRef(node: JsonNode): TypeRef {
        return when (schemaTypeValue(node)) {
            "string" -> TypeRef.Primitive(PrimitiveType.STRING)
            "integer" -> TypeRef.Primitive(PrimitiveType.INT)
            "number" -> TypeRef.Primitive(PrimitiveType.DOUBLE)
            "boolean" -> TypeRef.Primitive(PrimitiveType.BOOLEAN)
            // TODO v1: format keyword (date, date-time, etc.) — treated as STRING
            else -> TypeRef.Primitive(PrimitiveType.ANY)
        }
    }

    fun inferEnumBaseType(node: JsonNode): PrimitiveType {
        return when (schemaTypeValue(node)) {
            "integer" -> PrimitiveType.INT
            "number" -> PrimitiveType.DOUBLE
            else -> PrimitiveType.STRING
        }
    }

    fun extractDoc(node: JsonNode): String? {
        val title = node.get("title")?.textValue()
        val desc = node.get("description")?.textValue()
        return listOfNotNull(title, desc).joinToString("\n").takeIf { it.isNotBlank() }
    }

    fun extractConstraints(node: JsonNode): List<Constraint> {
        val cs = mutableListOf<Constraint>()
        node.get("minLength")?.intValue()?.let { cs.add(Constraint.MinLength(it)) }
        node.get("maxLength")?.intValue()?.let { cs.add(Constraint.MaxLength(it)) }
        node.get("pattern")?.textValue()?.let { cs.add(Constraint.Pattern(it)) }
        node.get("minimum")?.let { cs.add(Constraint.MinValue(it.asText())) }
        node.get("maximum")?.let { cs.add(Constraint.MaxValue(it.asText())) }
        node.get("minItems")?.intValue()?.let { cs.add(Constraint.MinItems(it)) }
        node.get("maxItems")?.intValue()?.let { cs.add(Constraint.MaxItems(it)) }
        return cs
    }

    /** Suffix duplicate SCREAMING_SNAKE_CASE names with `_2`, `_3`, etc. */
    private fun deduplicateEnumNames(values: List<EnumValue>): List<EnumValue> {
        val seen = mutableMapOf<String, Int>()
        return values.map { ev ->
            val count = seen.merge(ev.kotlinName, 1, Int::plus)!!
            if (count == 1) ev else ev.copy(kotlinName = "${ev.kotlinName}_$count")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Name sanitization utilities (internal for testing)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Convert an arbitrary schema name to PascalCase Kotlin identifier.
 * Examples:
 *   `runs-javascript` → `RunsJavascript`
 *   `pre-if`          → `PreIf`
 *   `cancel_in_prog`  → `CancelInProg`
 *   `ARM32`           → `Arm32`
 */
internal fun toPascalCase(name: String): String {
    if (name.isEmpty()) return "Unknown"
    val words = splitIntoWords(name)
    val joined = if (words.isEmpty()) {
        name.replaceFirstChar { it.uppercaseChar() }
    } else {
        // Use w.lowercase().replaceFirstChar to avoid accidental double-tail concatenation.
        words.joinToString("") { w -> w.lowercase().replaceFirstChar { it.uppercaseChar() } }
    }
    return sanitizeIdentifier(joined)
}

/**
 * Convert an arbitrary schema name to camelCase Kotlin identifier.
 * Examples:
 *   `cancel-in-progress` → `cancelInProgress`
 *   `MyProp`             → `myProp`
 */
internal fun toCamelCase(name: String): String {
    val pascal = toPascalCase(name)
    if (pascal.isEmpty()) return pascal
    return if (pascal.startsWith("_") && pascal.length > 1 && pascal[1].isDigit()) {
        // Preserve the leading underscore that guards digit-start names
        "_" + pascal[1].lowercaseChar() + pascal.substring(2)
    } else {
        pascal[0].lowercaseChar() + pascal.substring(1)
    }
}

/**
 * Convert an arbitrary value string to SCREAMING_SNAKE_CASE Kotlin enum constant.
 * Examples:
 *   `cancel-in-progress` → `CANCEL_IN_PROGRESS`
 *   `ARM32`              → `ARM32`
 *   `node12`             → `NODE12`
 */
internal fun toScreamingSnakeCase(value: String): String {
    if (value.isEmpty()) return "EMPTY"

    // Replace common delimiters with underscore, strip anything else non-alphanumeric.
    val normalized = value
        .replace(Regex("[-. /]+"), "_")
        .replace(Regex("[^A-Za-z0-9_]"), "_")

    val upper = normalized.uppercase()
    // Collapse multiple underscores and trim surrounding ones.
    val trimmed = upper.replace(Regex("_+"), "_").trimStart('_').trimEnd('_')

    if (trimmed.isEmpty()) return "VALUE"
    return if (trimmed[0].isDigit()) "_$trimmed" else trimmed
}

// ── Word splitting helpers ─────────────────────────────────────────────────

/**
 * Split a name string into constituent words, handling kebab-case, snake_case,
 * dot.case, and PascalCase/camelCase mixed inputs.
 */
private fun splitIntoWords(name: String): List<String> {
    val parts = name.split(Regex("[-_. /]+"))
    return parts.flatMap { part ->
        if (part.isEmpty()) emptyList() else splitCamelCase(part)
    }.filter { it.isNotEmpty() }
}

/**
 * Split a camelCase/PascalCase string into words.
 * `RunsJavascript` → `[Runs, Javascript]`
 * `ARM32`          → `[ARM32]`  (run of uppercase treated as one word)
 */
private fun splitCamelCase(s: String): List<String> {
    val marked = s
        .replace(Regex("([a-z])([A-Z])"), "$1|$2")
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1|$2")
    return marked.split("|")
}

/** Strip non-alphanumeric characters and prefix digit-start names with `_`. */
private fun sanitizeIdentifier(s: String): String {
    val clean = s.replace(Regex("[^A-Za-z0-9]"), "")
    if (clean.isEmpty()) return "Unknown"
    return if (clean[0].isDigit()) "_$clean" else clean
}
