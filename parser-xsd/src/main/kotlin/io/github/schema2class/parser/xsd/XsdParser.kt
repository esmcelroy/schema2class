package io.github.schema2class.parser.xsd

import io.github.schema2class.core.ir.Constraint
import io.github.schema2class.core.ir.EnumValue
import io.github.schema2class.core.ir.PrimitiveType
import io.github.schema2class.core.ir.PropertyDefinition
import io.github.schema2class.core.ir.SchemaModel
import io.github.schema2class.core.ir.SourceFormat
import io.github.schema2class.core.ir.TypeDefinition
import io.github.schema2class.core.ir.TypeRef
import io.github.schema2class.core.naming.NamespacePackageMapper
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

private const val XSD_NS = "http://www.w3.org/2001/XMLSchema"

class XsdParser {

    fun parse(inputStream: InputStream, packageName: String): SchemaModel {
        val root = parseDocument(inputStream)
        return ParseContext(listOf(root), targetNamespaceOf(root), packageName).parse()
    }

    fun parse(file: File, packageName: String): SchemaModel =
        file.inputStream().use { parse(it, packageName) }

    /** Derives the Kotlin package from the schema's targetNamespace via [packageMapper]. */
    fun parse(
        inputStream: InputStream,
        packageMapper: NamespacePackageMapper = NamespacePackageMapper(),
    ): SchemaModel {
        val root = parseDocument(inputStream)
        val namespace = targetNamespaceOf(root)
        return ParseContext(
            roots = listOf(root),
            targetNamespace = namespace,
            packageName = packageMapper.toPackage(namespace),
            fallbackMapper = packageMapper,
        ).parse()
    }

    /** Derives the Kotlin package from the schema's targetNamespace via [packageMapper]. */
    fun parse(
        file: File,
        packageMapper: NamespacePackageMapper = NamespacePackageMapper(),
    ): SchemaModel =
        file.inputStream().use { parse(it, packageMapper) }

    /**
     * Parses [file] and everything reachable through xs:include and xs:import, returning
     * one [SchemaModel] per namespace (the entry file's namespace first).
     *
     * - Each document is parsed once, keyed by canonical path; include/import cycles are safe.
     * - xs:include splices the included document into the including namespace; a chameleon
     *   include (no targetNamespace of its own) adopts the includer's namespace.
     * - xs:import namespaces map to their own packages via [packageMapper.toPackages], so
     *   cross-namespace references become cross-package TypeRef.Named. Imports without a
     *   resolvable schemaLocation still get a stable package for references; a warning is
     *   emitted and no model is generated for them.
     * - Remote (http/https) schemaLocations are not fetched; a warning is emitted.
     */
    fun parseWithImports(
        file: File,
        packageMapper: NamespacePackageMapper = NamespacePackageMapper(),
    ): List<SchemaModel> {
        val loader = SchemaSetLoader()
        loader.load(file.canonicalFile, adoptedNamespace = null)

        val rootsByNamespace = LinkedHashMap<String?, MutableList<Element>>()
        for (doc in loader.docs.values) {
            rootsByNamespace.getOrPut(doc.effectiveNamespace) { mutableListOf() }.add(doc.root)
        }

        val allNamespaces = LinkedHashSet<String?>(rootsByNamespace.keys)
        allNamespaces.addAll(loader.declaredImportNamespaces)
        val packages = packageMapper.toPackages(allNamespaces)

        return rootsByNamespace.map { (namespace, roots) ->
            ParseContext(
                roots = roots,
                targetNamespace = namespace,
                packageName = packages.getValue(namespace),
                packagesByNamespace = packages,
                fallbackMapper = packageMapper,
            ).parse()
        }
    }

    private fun parseDocument(inputStream: InputStream): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(inputStream).documentElement
    }

    private fun targetNamespaceOf(root: Element): String? =
        root.getAttribute("targetNamespace").ifBlank { null }

    private fun warn(message: String) {
        System.err.println("schema2class: WARNING: $message")
    }

    // ── Multi-file loading ────────────────────────────────────────────────────

    private class LoadedDoc(val root: Element, val effectiveNamespace: String?)

    private inner class SchemaSetLoader {
        /** Canonical path → document, in load order (entry document first). */
        val docs = LinkedHashMap<String, LoadedDoc>()

        /** Namespaces declared on xs:import, whether or not their documents were loadable. */
        val declaredImportNamespaces = LinkedHashSet<String>()

        fun load(file: File, adoptedNamespace: String?) {
            val key = file.canonicalPath
            if (docs.containsKey(key)) return
            if (!file.isFile) {
                warn("schemaLocation does not exist: $file")
                return
            }
            val root = file.inputStream().use { parseDocument(it) }
            val effectiveNamespace = targetNamespaceOf(root) ?: adoptedNamespace
            // Register before recursing so include/import cycles terminate.
            docs[key] = LoadedDoc(root, effectiveNamespace)

            for (include in xsdChildren(root, "include")) {
                val location = include.getAttribute("schemaLocation").ifBlank { null } ?: continue
                resolveLocation(file, location)?.let { load(it, effectiveNamespace) }
            }
            for (import in xsdChildren(root, "import")) {
                import.getAttribute("namespace").ifBlank { null }
                    ?.let { declaredImportNamespaces.add(it) }
                val location = import.getAttribute("schemaLocation").ifBlank { null }
                if (location == null) {
                    warn(
                        "xs:import for namespace '${import.getAttribute("namespace")}' has no " +
                            "schemaLocation; its types resolve by derived package only",
                    )
                    continue
                }
                resolveLocation(file, location)?.let { load(it, adoptedNamespace = null) }
            }
        }

        private fun resolveLocation(from: File, location: String): File? = when {
            location.startsWith("http://") || location.startsWith("https://") -> {
                warn("remote schemaLocation is not fetched: $location")
                null
            }
            File(location).isAbsolute -> File(location).canonicalFile
            else -> File(from.parentFile, location).canonicalFile
        }
    }

    // ── Parsing one namespace (one or more documents) into a SchemaModel ─────

    private inner class ParseContext(
        private val roots: List<Element>,
        private val targetNamespace: String?,
        private val packageName: String,
        private val packagesByNamespace: Map<String?, String> = emptyMap(),
        private val fallbackMapper: NamespacePackageMapper = NamespacePackageMapper(),
    ) {
        private val types = mutableListOf<TypeDefinition>()

        fun parse(): SchemaModel {
            // Top-level simpleType first — referenced by complexTypes
            for (root in roots) {
                xsdChildren(root, "simpleType").forEach { el ->
                    el.getAttribute("name").ifBlank { null }?.let { processSimpleType(el, it) }
                }
            }
            for (root in roots) {
                xsdChildren(root, "complexType").forEach { el ->
                    el.getAttribute("name").ifBlank { null }?.let { processComplexType(el, it) }
                }
            }
            for (root in roots) {
                xsdChildren(root, "element").forEach { processTopLevelElement(it) }
            }

            return SchemaModel(
                namespace = targetNamespace,
                packageName = packageName,
                types = types,
                sourceFormat = SourceFormat.XSD,
            )
        }

        // ── Simple types ──────────────────────────────────────────────────────

        private fun processSimpleType(el: Element, schemaName: String) {
            val restriction = xsdChild(el, "restriction")
            if (restriction == null) {
                addAliasType(el, schemaName, null)
                return
            }
            val enumerations = xsdChildren(restriction, "enumeration")
            if (enumerations.isNotEmpty()) {
                addEnumType(el, schemaName, restriction, enumerations)
            } else {
                addAliasType(el, schemaName, restriction)
            }
        }

        private fun addEnumType(
            typeEl: Element,
            schemaName: String,
            restriction: Element,
            enumerations: List<Element>,
        ) {
            val baseType = resolveBuiltinType(restriction.getAttribute("base")) ?: PrimitiveType.STRING
            val seenNames = mutableMapOf<String, Int>()

            val values = enumerations.map { enumEl ->
                val serializedValue = enumEl.getAttribute("value")
                val docEl = xsdChild(enumEl, "annotation")?.let { xsdChild(it, "documentation") }
                val rawName = docEl?.let { findCctsName(it) } ?: serializedValue
                val base = sanitizeEnumConstantName(rawName)
                val count = seenNames.merge(base, 1, Int::plus)!!
                val kotlinName = if (count == 1) base else "${base}_$count"
                val description = docEl?.let { findCctsDescription(it) }
                EnumValue(serializedValue = serializedValue, kotlinName = kotlinName, documentation = description)
            }

            types += TypeDefinition.EnumType(
                schemaName = schemaName,
                kotlinName = toPascalCase(schemaName),
                documentation = extractTypeDoc(typeEl),
                values = values,
                baseType = baseType,
            )
        }

        private fun addAliasType(typeEl: Element, schemaName: String, restriction: Element?) {
            val base = restriction?.getAttribute("base")?.ifBlank { null }
            val primitiveType = base?.let { resolveBuiltinType(it) } ?: PrimitiveType.STRING
            types += TypeDefinition.AliasType(
                schemaName = schemaName,
                kotlinName = toPascalCase(schemaName),
                documentation = extractTypeDoc(typeEl),
                aliasedType = TypeRef.Primitive(primitiveType),
                constraints = restriction?.let { buildConstraints(it) } ?: emptyList(),
            )
        }

        private fun buildConstraints(restriction: Element): List<Constraint> {
            val result = mutableListOf<Constraint>()
            xsdChild(restriction, "minLength")?.getAttribute("value")?.toIntOrNull()
                ?.let { result += Constraint.MinLength(it) }
            xsdChild(restriction, "maxLength")?.getAttribute("value")?.toIntOrNull()
                ?.let { result += Constraint.MaxLength(it) }
            xsdChild(restriction, "pattern")?.getAttribute("value")?.ifBlank { null }
                ?.let { result += Constraint.Pattern(it) }
            xsdChild(restriction, "minInclusive")?.getAttribute("value")?.ifBlank { null }
                ?.let { result += Constraint.MinValue(it) }
            xsdChild(restriction, "maxInclusive")?.getAttribute("value")?.ifBlank { null }
                ?.let { result += Constraint.MaxValue(it) }
            return result
        }

        // ── Complex types ─────────────────────────────────────────────────────

        private fun processComplexType(el: Element, schemaName: String) {
            val simpleContent = xsdChild(el, "simpleContent")
            val complexContent = xsdChild(el, "complexContent")
            when {
                simpleContent != null -> processSimpleContent(el, schemaName, simpleContent)
                complexContent != null -> processComplexContent(el, schemaName, complexContent)
                else -> processPlainComplexType(el, schemaName)
            }
        }

        private fun processPlainComplexType(el: Element, schemaName: String) {
            val sequence = xsdChild(el, "sequence") ?: xsdChild(el, "all")
            val properties = mutableListOf<PropertyDefinition>()
            sequence?.let { seq ->
                xsdChildren(seq, "element").forEach { properties += buildElementProperty(it, schemaName) }
            }
            xsdChildren(el, "attribute").forEach { properties += buildAttributeProperty(it) }
            types += TypeDefinition.ComplexType(
                schemaName = schemaName,
                kotlinName = toPascalCase(schemaName),
                documentation = extractTypeDoc(el),
                properties = properties,
            )
        }

        private fun processSimpleContent(typeEl: Element, schemaName: String, simpleContent: Element) {
            val extension = xsdChild(simpleContent, "extension") ?: return
            val base = extension.getAttribute("base")
            val contentProperty = PropertyDefinition(
                schemaName = "value",
                kotlinName = "value",
                type = resolveTypeRef(base, extension),
                nullable = false,
                defaultValue = null,
                documentation = null,
            )
            val attributes = xsdChildren(extension, "attribute").map { buildAttributeProperty(it) }
            types += TypeDefinition.ComplexType(
                schemaName = schemaName,
                kotlinName = toPascalCase(schemaName),
                documentation = extractTypeDoc(typeEl),
                properties = attributes,
                contentProperty = contentProperty,
            )
        }

        // Basic complexContent+extension support; deep inheritance is schema2class-8kr.
        private fun processComplexContent(typeEl: Element, schemaName: String, complexContent: Element) {
            val extension = xsdChild(complexContent, "extension") ?: return
            val base = extension.getAttribute("base").ifBlank { null }
            val superType = base?.let { resolveTypeRef(it, extension) }
            val sequence = xsdChild(extension, "sequence") ?: xsdChild(extension, "all")
            val properties = mutableListOf<PropertyDefinition>()
            sequence?.let { seq ->
                xsdChildren(seq, "element").forEach { properties += buildElementProperty(it, schemaName) }
            }
            xsdChildren(extension, "attribute").forEach { properties += buildAttributeProperty(it) }
            types += TypeDefinition.ComplexType(
                schemaName = schemaName,
                kotlinName = toPascalCase(schemaName),
                documentation = extractTypeDoc(typeEl),
                properties = properties,
                superType = superType,
            )
        }

        // ── Top-level elements ────────────────────────────────────────────────

        private fun processTopLevelElement(el: Element) {
            val name = el.getAttribute("name").ifBlank { null } ?: return
            val inlineComplex = xsdChild(el, "complexType")
            val inlineSimple = xsdChild(el, "simpleType")
            when {
                inlineComplex != null -> processComplexType(inlineComplex, name)
                inlineSimple != null -> processSimpleType(inlineSimple, name)
                // type="ns:SomeType" reference — no new type definition here
            }
        }

        // ── Property builders ─────────────────────────────────────────────────

        private fun buildElementProperty(el: Element, parentTypeName: String): PropertyDefinition {
            val name = el.getAttribute("name")
            val minOccurs = el.getAttribute("minOccurs").ifBlank { "1" }
                .let { if (it == "unbounded") Int.MAX_VALUE else it.toIntOrNull() ?: 1 }
            val maxOccurs = el.getAttribute("maxOccurs").ifBlank { "1" }
                .let { if (it == "unbounded") Int.MAX_VALUE else it.toIntOrNull() ?: 1 }
            val isList = maxOccurs > 1
            val isNullable = minOccurs == 0 && !isList

            val typeAttr = el.getAttribute("type").ifBlank { null }
            val inlineComplex = xsdChild(el, "complexType")
            val inlineSimple = xsdChild(el, "simpleType")

            val elementTypeRef: TypeRef = when {
                inlineComplex != null -> {
                    val inlineName = "${toPascalCase(parentTypeName)}_${toPascalCase(name)}"
                    processComplexType(inlineComplex, inlineName)
                    TypeRef.Named(toPascalCase(inlineName))
                }
                inlineSimple != null -> {
                    val inlineName = "${toPascalCase(parentTypeName)}_${toPascalCase(name)}"
                    processSimpleType(inlineSimple, inlineName)
                    TypeRef.Named(toPascalCase(inlineName))
                }
                typeAttr != null -> resolveTypeRef(typeAttr, el)
                else -> TypeRef.Primitive(PrimitiveType.ANY)
            }

            return PropertyDefinition(
                schemaName = name,
                kotlinName = toCamelCase(name),
                type = if (isList) TypeRef.ListOf(elementTypeRef) else elementTypeRef,
                nullable = isNullable,
                defaultValue = null,
                documentation = extractTypeDoc(el),
            )
        }

        private fun buildAttributeProperty(el: Element): PropertyDefinition {
            val name = el.getAttribute("name")
            val use = el.getAttribute("use").ifBlank { "optional" }
            val typeAttr = el.getAttribute("type").ifBlank { null }
            val typeRef = typeAttr?.let { resolveTypeRef(it, el) } ?: TypeRef.Primitive(PrimitiveType.STRING)
            return PropertyDefinition(
                schemaName = name,
                kotlinName = toCamelCase(name),
                type = typeRef,
                nullable = use != "required",
                defaultValue = null,
                documentation = extractTypeDoc(el),
            )
        }

        // ── Type resolution ───────────────────────────────────────────────────

        /**
         * Resolves a QName in the scope of [context] (prefix bindings can be declared on
         * any ancestor element). Same-namespace references stay package-local; references
         * into other namespaces become cross-package [TypeRef.Named].
         */
        private fun resolveTypeRef(qname: String, context: Element): TypeRef {
            if (qname.isBlank()) return TypeRef.Primitive(PrimitiveType.ANY)
            val colonIdx = qname.indexOf(':')
            val prefix = if (colonIdx >= 0) qname.substring(0, colonIdx) else null
            val localName = if (colonIdx >= 0) qname.substring(colonIdx + 1) else qname
            val nsUri = context.lookupNamespaceURI(prefix)
            if (nsUri == XSD_NS) {
                builtinToPrimitive(localName)?.let { return TypeRef.Primitive(it) }
            }
            val kotlinName = toPascalCase(localName)
            return if (nsUri == null || nsUri == targetNamespace) {
                TypeRef.Named(kotlinName)
            } else {
                TypeRef.Named(
                    name = kotlinName,
                    packageName = packagesByNamespace[nsUri] ?: fallbackMapper.toPackage(nsUri),
                )
            }
        }

        private fun resolveBuiltinType(qname: String): PrimitiveType? =
            builtinToPrimitive(if (':' in qname) qname.substringAfter(':') else qname)

        private fun builtinToPrimitive(localName: String): PrimitiveType? = when (localName) {
            "string", "token", "normalizedString", "NMTOKEN", "NMTOKENS", "Name", "NCName",
            "ID", "IDREF", "IDREFS", "ENTITY", "ENTITIES", "language",
            "gYear", "gYearMonth", "gMonth", "gMonthDay", "gDay" -> PrimitiveType.STRING
            "integer", "long", "nonNegativeInteger", "positiveInteger", "nonPositiveInteger",
            "negativeInteger", "unsignedLong" -> PrimitiveType.LONG
            "int", "short", "byte", "unsignedInt", "unsignedShort", "unsignedByte" -> PrimitiveType.INT
            "decimal" -> PrimitiveType.DECIMAL
            "double" -> PrimitiveType.DOUBLE
            "float" -> PrimitiveType.FLOAT
            "boolean" -> PrimitiveType.BOOLEAN
            "date" -> PrimitiveType.DATE
            "dateTime" -> PrimitiveType.DATE_TIME
            "time" -> PrimitiveType.DATE_TIME
            "duration", "yearMonthDuration", "dayTimeDuration" -> PrimitiveType.DURATION
            "base64Binary", "hexBinary" -> PrimitiveType.BYTES
            "anyURI" -> PrimitiveType.URI
            "anyType", "anySimpleType" -> PrimitiveType.ANY
            else -> null
        }

        // ── Documentation helpers ─────────────────────────────────────────────

        private fun extractTypeDoc(el: Element): String? {
            val annotation = xsdChild(el, "annotation") ?: return null
            val docEl = xsdChild(annotation, "documentation") ?: return null
            return findCctsDescription(docEl)?.ifBlank { null }
                ?: docEl.textContent.trim().ifBlank { null }
        }

        private fun findCctsName(docEl: Element): String? {
            val nodes = docEl.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node is Element && node.localName == "Name") return node.textContent.trim().ifBlank { null }
            }
            return null
        }

        private fun findCctsDescription(docEl: Element): String? {
            val nodes = docEl.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node is Element && node.localName == "Description") return node.textContent.trim().ifBlank { null }
            }
            return null
        }

        // ── Name sanitization ─────────────────────────────────────────────────

        private fun sanitizeEnumConstantName(name: String): String {
            val screaming = toScreamingSnakeCase(name)
            return if (screaming.firstOrNull()?.isDigit() == true) "VALUE_$screaming" else screaming
        }

        private fun toPascalCase(input: String): String =
            splitIntoWords(input).joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }

        private fun toCamelCase(input: String): String {
            val words = splitIntoWords(input)
            return words.mapIndexed { i, w ->
                if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar(Char::uppercase)
            }.joinToString("")
        }

        private fun toScreamingSnakeCase(input: String): String =
            splitIntoWords(input).joinToString("_") { it.uppercase() }

        private fun splitIntoWords(input: String): List<String> {
            val byDelimiter = input.split(Regex("[-_. /]+"))
            return byDelimiter.flatMap { splitCamelCase(it) }.filter { it.isNotEmpty() }
        }

        private fun splitCamelCase(input: String): List<String> {
            if (input.isEmpty()) return emptyList()
            val result = mutableListOf<String>()
            val current = StringBuilder()
            for (i in input.indices) {
                val ch = input[i]
                when {
                    ch.isUpperCase() && i > 0 && input[i - 1].isLowerCase() -> {
                        result += current.toString()
                        current.clear()
                        current.append(ch)
                    }
                    ch.isUpperCase() && i > 0 && input[i - 1].isUpperCase() &&
                            i + 1 < input.length && input[i + 1].isLowerCase() -> {
                        result += current.toString()
                        current.clear()
                        current.append(ch)
                    }
                    else -> current.append(ch)
                }
            }
            if (current.isNotEmpty()) result += current.toString()
            return result
        }
    }
}

// ── DOM helpers (shared by loader and parse contexts) ────────────────────────

private fun xsdChildren(parent: Element, localName: String): List<Element> {
    val result = mutableListOf<Element>()
    val children = parent.childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node is Element && node.localName == localName && node.namespaceURI == XSD_NS) {
            result.add(node)
        }
    }
    return result
}

private fun xsdChild(parent: Element, localName: String): Element? =
    xsdChildren(parent, localName).firstOrNull()
