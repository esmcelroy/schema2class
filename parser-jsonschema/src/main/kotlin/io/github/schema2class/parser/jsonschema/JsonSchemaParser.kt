package io.github.schema2class.parser.jsonschema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.schema2class.core.ir.Constraint
import io.github.schema2class.core.ir.EnumValue
import io.github.schema2class.core.ir.InheritanceFlattener
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
 * - `not`, `if/then/else` combiners
 * - External `$ref` (cross-file) — emits `TypeRef.Named` with a warning
 * - Unknown JSON Schema `format` keywords — treated as plain STRING
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

    /**
     * Parses [file] and every document reachable through external `$ref`s
     * (relative paths like `common.json#/definitions/Address`), returning one
     * [SchemaModel] per document — the entry document first.
     *
     * - Each document is parsed once, keyed by canonical path; cross-document
     *   ref cycles are safe (external refs enqueue, they never recurse).
     * - The entry document gets [packageName]; every other document gets
     *   `packageName.<sanitized file stem>` (deduplicated with numeric suffixes),
     *   so shared types generate a single class in a stable package.
     * - Unresolvable files fall back to the single-document behavior: a warning
     *   and a same-package `TypeRef.Named`.
     */
    fun parseWithRefs(file: File, packageName: String): List<SchemaModel> {
        val entry = file.canonicalFile
        val packagesByPath = LinkedHashMap<String, String>()
        val usedPackages = mutableSetOf(packageName)
        packagesByPath[entry.path] = packageName

        fun packageFor(doc: File): String = packagesByPath.getOrPut(doc.path) {
            val stem = doc.name
                .removeSuffix(".json").removeSuffix(".schema")
                .lowercase().replace(Regex("[^a-z0-9]"), "_").trim('_')
                .ifBlank { "doc" }
                .let { if (it.first().isDigit()) "_$it" else it }
            var candidate = "$packageName.$stem"
            var counter = 2
            while (!usedPackages.add(candidate)) {
                candidate = "$packageName.${stem}_${counter++}"
            }
            candidate
        }

        val queue = ArrayDeque(listOf(entry))
        val models = LinkedHashMap<String, SchemaModel>()

        while (queue.isNotEmpty()) {
            val doc = queue.removeFirst()
            if (models.containsKey(doc.path)) continue

            val resolver: (String) -> TypeRef = resolver@{ refPath ->
                val filePart = refPath.substringBefore('#')
                val pointer = refPath.substringAfter('#', missingDelimiterValue = "")
                val target = File(doc.parentFile, filePart).canonicalFile
                if (!target.isFile) {
                    System.err.println(
                        "Warning: external \$ref target not found: $refPath (from ${doc.name})",
                    )
                    val simpleName = refPath.substringAfterLast("/").substringAfterLast("#")
                        .removeSuffix(".json").removeSuffix(".schema")
                    return@resolver TypeRef.Named(toPascalCase(simpleName.ifEmpty { "External" }))
                }
                if (!models.containsKey(target.path)) queue.addLast(target)
                val name = pointer.substringAfterLast('/').ifBlank { null }
                    ?: rootTypeName(mapper.readTree(target))
                    ?: target.name.removeSuffix(".json").removeSuffix(".schema")
                TypeRef.Named(toPascalCase(name), packageName = packageFor(target))
            }

            val root = doc.inputStream().use { mapper.readTree(it) }
            models[doc.path] = ParseContext(packageFor(doc), root, resolver).parse()
        }

        return models.values.toList()
    }
}

/** The name parse() will give a document's root object type, or null if it has none. */
private fun rootTypeName(root: JsonNode): String? {
    if (root.get("type")?.textValue() != "object" && !root.has("properties")) return null
    return root.get("title")?.textValue()
        ?: root.get("\$id")?.textValue()
            ?.substringAfterLast("/")?.removeSuffix(".json")?.removeSuffix(".schema")
        ?: "Root"
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal parse context — one instance per parse() call, not thread-safe.
// ─────────────────────────────────────────────────────────────────────────────

private class ParseContext(
    val packageName: String,
    val root: JsonNode,
    /** Resolves external (non-`#`) refs in multi-document parses; null = warn and degrade. */
    val externalResolver: ((String) -> TypeRef)? = null,
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

        val model = SchemaModel(
            namespace = namespace,
            packageName = packageName,
            types = types,
            sourceFormat = SourceFormat.JSON_SCHEMA,
        )
        // Resolve allOf-derived superType chains into flattened properties.
        return InheritanceFlattener.flatten(listOf(model)).single()
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

            node.has("allOf") -> {
                // Register a placeholder before recursing to break reference cycles.
                types.add(TypeDefinition.ComplexType(schemaName, kotlinName, doc, emptyList()))
                val built = buildAllOfType(schemaName, kotlinName, doc, node)
                replace(schemaName, built)
            }

            dictionaryValueSchema(node) != null -> {
                val valueRef = resolveTypeRef(dictionaryValueSchema(node)!!, "${schemaName}_Value")
                types.add(
                    TypeDefinition.AliasType(
                        schemaName, kotlinName, doc, TypeRef.MapOf(value = valueRef),
                    ),
                )
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
        dictionaryValueSchema(node)?.let { valueSchema ->
            return TypeRef.MapOf(
                value = resolveTypeRef(valueSchema, "${suggestedName}_Value"),
            )
        }
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
            externalResolver?.let { return it(refPath) }
            // Single-document parse: emit named ref with null package and warn.
            val simpleName = refPath
                .substringAfterLast("/")
                .substringAfterLast("#")
                .removeSuffix(".json")
                .removeSuffix(".schema")
            System.err.println(
                "Warning: external \$ref outside parseWithRefs, emitting named ref: $refPath",
            )
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
            val constraints = extractConstraints(propNode)
            // Suggested name for anonymous inline types: ParentType_propertyName
            val suggestedTypeName = "${schemaName}_${propSchemaName}"
            val typeRef = resolveTypeRef(propNode, suggestedTypeName)
            val defaultValue = kotlinDefaultLiteral(propNode.get("default"), propSchemaName)

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
        // OpenAPI-style discriminator: { "propertyName": "petType", ... }
        val discriminator = node.get("discriminator")
            ?.get("propertyName")
            ?.textValue()
            ?.ifBlank { null }
        return TypeDefinition.UnionType(
            schemaName = schemaName,
            kotlinName = kotlinName,
            documentation = doc,
            variants = variants,
            discriminatorProperty = discriminator,
        )
    }

    /**
     * allOf combiner. Two patterns:
     * - Inheritance: a pure-`$ref` branch becomes [TypeDefinition.ComplexType.superType]
     *   (resolved into flattened properties by [InheritanceFlattener] after parsing);
     *   inline object branches merge their properties, last branch wins on collisions.
     * - Pure intersection (no refs, no properties): constraints from all branches
     *   flatten onto a single [TypeDefinition.AliasType].
     */
    fun buildAllOfType(
        schemaName: String,
        kotlinName: String,
        doc: String?,
        node: JsonNode,
    ): TypeDefinition {
        val branches = node.get("allOf").toList()
        val (refBranches, inlineBranches) = branches.partition {
            it.has("\$ref") && it.size() == 1
        }

        // Pattern 2: constraint intersection with no structural content.
        if (refBranches.isEmpty() && inlineBranches.none { it.has("properties") }) {
            val constraints = inlineBranches.flatMap { extractConstraints(it) }
            val baseRef = inlineBranches.firstNotNullOfOrNull { branch ->
                schemaTypeValue(branch)?.let { primitiveTypeRef(branch) }
            } ?: TypeRef.Primitive(PrimitiveType.ANY)
            return TypeDefinition.AliasType(schemaName, kotlinName, doc, baseRef, constraints)
        }

        val superType = refBranches.firstOrNull()
            ?.let { resolveRefToTypeRef(it.get("\$ref").textValue()) as? TypeRef.Named }
        refBranches.drop(1).forEach {
            System.err.println(
                "Warning: allOf of '$schemaName' has multiple \$ref branches; " +
                    "only the first becomes the base — '${it.get("\$ref").textValue()}' ignored",
            )
        }

        // Merge inline branches; last branch wins on duplicate property names.
        val merged = LinkedHashMap<String, PropertyDefinition>()
        for (branch in inlineBranches) {
            buildComplexType(schemaName, kotlinName, doc, branch).properties.forEach {
                merged[it.schemaName] = it
            }
        }

        return TypeDefinition.ComplexType(
            schemaName = schemaName,
            kotlinName = kotlinName,
            documentation = doc,
            properties = merged.values.toList(),
            superType = superType,
        )
    }

    /**
     * A dictionary-shaped object: no fixed properties, values described by
     * patternProperties (single pattern) or a schema-valued additionalProperties.
     * Both mean "string-keyed map of T". Returns the value schema, or null when
     * the node is not a dictionary.
     */
    fun dictionaryValueSchema(node: JsonNode): JsonNode? {
        if (node.has("properties") || node.has("\$ref") || node.has("enum") ||
            node.has("oneOf") || node.has("anyOf") || node.has("allOf")
        ) {
            return null
        }
        val isObject = schemaTypeValue(node) == "object" ||
            node.has("patternProperties") || node.has("additionalProperties")
        if (!isObject) return null

        node.get("patternProperties")?.let { patterns ->
            val schemas = patterns.fields().asSequence().map { it.value }.toList()
            // Multiple patterns with different value schemas have no single map type.
            if (schemas.size == 1 && schemas[0].isObject) return schemas[0]
        }
        node.get("additionalProperties")?.let { ap ->
            // Boolean additionalProperties is a constraint, not a value schema.
            if (ap.isObject) return ap
        }
        return null
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
            "string" -> TypeRef.Primitive(stringFormatPrimitive(node))
            "integer" -> TypeRef.Primitive(PrimitiveType.INT)
            "number" -> TypeRef.Primitive(PrimitiveType.DOUBLE)
            "boolean" -> TypeRef.Primitive(PrimitiveType.BOOLEAN)
            else -> TypeRef.Primitive(PrimitiveType.ANY)
        }
    }

    private fun stringFormatPrimitive(node: JsonNode): PrimitiveType =
        when (node.get("format")?.textValue()) {
            "uri", "uri-reference" -> PrimitiveType.URI
            "date" -> PrimitiveType.DATE
            "date-time" -> PrimitiveType.DATE_TIME
            "duration" -> PrimitiveType.DURATION
            else -> PrimitiveType.STRING
        }

    fun kotlinDefaultLiteral(defaultNode: JsonNode?, propSchemaName: String): String? {
        if (defaultNode == null) return null
        return when {
            defaultNode.isTextual -> quoteKotlinString(defaultNode.textValue())
            defaultNode.isNumber || defaultNode.isBoolean || defaultNode.isNull -> defaultNode.toString()
            else -> {
                System.err.println(
                    "Warning: skipping non-scalar default for JSON Schema property '$propSchemaName'",
                )
                null
            }
        }
    }

    private fun quoteKotlinString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> {
                    append('\\')
                    append('$')
                }
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                else -> {
                    if (ch < ' ') {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
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
