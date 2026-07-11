package ca.esmcelroy.schema2class.parser.xsd

import ca.esmcelroy.schema2class.core.ir.Constraint
import ca.esmcelroy.schema2class.core.ir.EnumValue
import ca.esmcelroy.schema2class.core.ir.InheritanceFlattener
import ca.esmcelroy.schema2class.core.ir.PrimitiveType
import ca.esmcelroy.schema2class.core.ir.PropertyDefinition
import ca.esmcelroy.schema2class.core.ir.PropertyKind
import ca.esmcelroy.schema2class.core.ir.SchemaModel
import ca.esmcelroy.schema2class.core.ir.SourceFormat
import ca.esmcelroy.schema2class.core.ir.TypeDefinition
import ca.esmcelroy.schema2class.core.ir.TypeRef
import ca.esmcelroy.schema2class.core.ir.UnionVariant
import ca.esmcelroy.schema2class.core.naming.NamespacePackageMapper
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream

internal const val XSD_NS = "http://www.w3.org/2001/XMLSchema"

class XsdParser {

    fun parse(inputStream: InputStream, packageName: String): SchemaModel {
        val root = parseDocument(inputStream)
        val model = ParseContext(listOf(root), targetNamespaceOf(root), packageName).parse()
        return InheritanceFlattener.flatten(listOf(model)).single()
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
        val model = ParseContext(
            roots = listOf(root),
            targetNamespace = namespace,
            packageName = packageMapper.toPackage(namespace),
            fallbackMapper = packageMapper,
        ).parse()
        return InheritanceFlattener.flatten(listOf(model)).single()
    }

    /** Derives the Kotlin package from the schema's targetNamespace via [packageMapper]. */
    fun parse(
        file: File,
        packageMapper: NamespacePackageMapper = NamespacePackageMapper(),
    ): SchemaModel =
        file.inputStream().use { parse(it, packageMapper) }

    /**
     * Parses already-extracted xs:schema document roots, grouped by targetNamespace.
     * Used by thin frontends such as WSDL where schemas are embedded in a larger XML
     * document. External schemaLocation loading is intentionally left to file-based
     * [parseWithImports].
     */
    fun parseSchemaRoots(
        roots: List<Element>,
        packageMapper: NamespacePackageMapper = NamespacePackageMapper(),
    ): List<SchemaModel> {
        val rootsByNamespace = LinkedHashMap<String?, MutableList<Element>>()
        for (root in roots) {
            rootsByNamespace.getOrPut(targetNamespaceOf(root)) { mutableListOf() }.add(root)
        }
        val packages = packageMapper.toPackages(rootsByNamespace.keys)
        val models = rootsByNamespace.map { (namespace, namespaceRoots) ->
            ParseContext(
                roots = namespaceRoots,
                targetNamespace = namespace,
                packageName = packages.getValue(namespace),
                packagesByNamespace = packages,
                fallbackMapper = packageMapper,
            ).parse()
        }
        return InheritanceFlattener.flatten(models)
    }

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

        val models = rootsByNamespace.map { (namespace, roots) ->
            ParseContext(
                roots = roots,
                targetNamespace = namespace,
                packageName = packages.getValue(namespace),
                packagesByNamespace = packages,
                fallbackMapper = packageMapper,
            ).parse()
        }
        // Cross-namespace extension chains (e.g. qualified types extending
        // unqualified base types) are resolved over the whole schema set.
        return InheritanceFlattener.flatten(models)
    }

    private fun parseDocument(inputStream: InputStream): Element {
        return SecureXml.parseDocument(inputStream)
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

        /** Top-level xs:group / xs:attributeGroup / xs:element definitions, by local name. */
        private val groupDefs = mutableMapOf<String, Element>()
        private val attributeGroupDefs = mutableMapOf<String, Element>()
        private val elementDefs = mutableMapOf<String, Element>()

        fun parse(): SchemaModel {
            for (root in roots) {
                xsdChildren(root, "group").forEach { el ->
                    el.getAttribute("name").ifBlank { null }?.let { groupDefs[it] = el }
                }
                xsdChildren(root, "attributeGroup").forEach { el ->
                    el.getAttribute("name").ifBlank { null }?.let { attributeGroupDefs[it] = el }
                }
                xsdChildren(root, "element").forEach { el ->
                    el.getAttribute("name").ifBlank { null }?.let { elementDefs[it] = el }
                }
            }
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
            val list = xsdChild(el, "list")
            if (list != null) {
                addListAliasType(el, schemaName, list)
                return
            }
            val union = xsdChild(el, "union")
            if (union != null) {
                addUnionSimpleType(el, schemaName, union)
                return
            }
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

        private fun addListAliasType(typeEl: Element, schemaName: String, list: Element) {
            val itemRef = list.getAttribute("itemType")
                .ifBlank { null }
                ?.let { resolveTypeRef(it, list) }
                ?: xsdChild(list, "simpleType")?.let { inline ->
                    val inlineName = "${toPascalCase(schemaName)}Item"
                    processSimpleType(inline, inlineName)
                    TypeRef.Named(toPascalCase(inlineName))
                }
                ?: TypeRef.Primitive(PrimitiveType.STRING)
            types += TypeDefinition.AliasType(
                schemaName = schemaName,
                kotlinName = toPascalCase(schemaName),
                documentation = extractTypeDoc(typeEl),
                aliasedType = TypeRef.ListOf(itemRef),
            )
        }

        private fun addUnionSimpleType(typeEl: Element, schemaName: String, union: Element) {
            val memberRefs = mutableListOf<TypeRef>()
            union.getAttribute("memberTypes")
                .split(Regex("\\s+"))
                .mapNotNull { it.ifBlank { null } }
                .forEach { memberRefs += resolveTypeRef(it, union) }
            xsdChildren(union, "simpleType").forEachIndexed { index, inline ->
                val inlineName = "${toPascalCase(schemaName)}Member${index + 1}"
                processSimpleType(inline, inlineName)
                memberRefs += TypeRef.Named(toPascalCase(inlineName))
            }

            val variants = distinguishableUnionVariants(memberRefs)
            if (variants == null) {
                types += TypeDefinition.AliasType(
                    schemaName = schemaName,
                    kotlinName = toPascalCase(schemaName),
                    documentation = extractTypeDoc(typeEl),
                    aliasedType = TypeRef.Primitive(PrimitiveType.STRING),
                )
            } else {
                types += TypeDefinition.UnionType(
                    schemaName = schemaName,
                    kotlinName = toPascalCase(schemaName),
                    documentation = extractTypeDoc(typeEl),
                    variants = variants,
                )
            }
        }

        private fun distinguishableUnionVariants(memberRefs: List<TypeRef>): List<UnionVariant>? {
            if (memberRefs.size < 2) return null
            if (memberRefs.any { it == TypeRef.Primitive(PrimitiveType.STRING) || it == TypeRef.Primitive(PrimitiveType.ANY) }) {
                return null
            }
            if (memberRefs.distinct().size != memberRefs.size) return null
            return memberRefs.map { UnionVariant(kotlinName = unionVariantName(it), type = it) }
        }

        private fun unionVariantName(ref: TypeRef): String = when (ref) {
            is TypeRef.Primitive -> "${toPascalCase(ref.type.name.lowercase())}Variant"
            is TypeRef.Named -> "${ref.name}Variant"
            is TypeRef.ListOf -> "ListVariant"
            is TypeRef.MapOf -> "MapVariant"
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
            xsdChild(restriction, "length")?.getAttribute("value")?.toIntOrNull()
                ?.let { result += Constraint.ExactLength(it) }
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
            xsdChild(restriction, "totalDigits")?.getAttribute("value")?.toIntOrNull()
                ?.let { result += Constraint.TotalDigits(it) }
            xsdChild(restriction, "fractionDigits")?.getAttribute("value")?.toIntOrNull()
                ?.let { result += Constraint.FractionDigits(it) }
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
            val particle = contentParticleOf(el)
            val attributes = collectAttributeProperties(el)

            // A choice that is the entire content model (no attributes, occurs once,
            // element-only branches) maps to a sealed union. Anything murkier keeps
            // the flattened nullable-properties mapping.
            if (particle != null && attributes.isEmpty() && isUnionMappableChoice(particle)) {
                val variants = xsdChildren(particle, "element").map { branch ->
                    val prop = buildElementProperty(branch, schemaName)
                    UnionVariant(
                        kotlinName = toPascalCase(prop.schemaName) + "Variant",
                        type = prop.type,
                    )
                }
                types += TypeDefinition.UnionType(
                    schemaName = schemaName,
                    kotlinName = toPascalCase(schemaName),
                    documentation = extractTypeDoc(el),
                    variants = variants,
                )
                return
            }

            val properties = mutableListOf<PropertyDefinition>()
            particle?.let { properties += collectElementProperties(it, schemaName) }
            properties += attributes
            types += TypeDefinition.ComplexType(
                schemaName = schemaName,
                kotlinName = toPascalCase(schemaName),
                documentation = extractTypeDoc(el),
                properties = properties,
            )
        }

        private fun isUnionMappableChoice(particle: Element): Boolean {
            if (particle.localName != "choice") return false
            if (particle.getAttribute("minOccurs").ifBlank { "1" } != "1") return false
            if (particle.getAttribute("maxOccurs").ifBlank { "1" } != "1") return false
            val children = particle.childNodes
            var elementCount = 0
            for (i in 0 until children.length) {
                val child = children.item(i) as? Element ?: continue
                if (child.namespaceURI != XSD_NS) continue
                when (child.localName) {
                    "element" -> elementCount++
                    "annotation" -> Unit
                    else -> return false // nested particles, wildcards, group refs
                }
            }
            return elementCount >= 2
        }

        // ── Particle and attribute collection (groups, nesting, choice) ──────

        /** The content particle of a complexType or derivation: sequence, all, choice, or group ref. */
        private fun contentParticleOf(parent: Element): Element? =
            xsdChild(parent, "sequence")
                ?: xsdChild(parent, "all")
                ?: xsdChild(parent, "choice")
                ?: xsdChild(parent, "group")

        /**
         * Walks a particle tree collecting element properties. Recurses through
         * nested sequence/all/choice particles and resolves xs:group refs by
         * inlining the group's particles ([visitedGroups] guards recursion).
         * Elements under xs:choice become nullable — only one branch appears on
         * the wire (mapping choice to a sealed type is schema2class-eq1).
         */
        private fun collectElementProperties(
            particle: Element,
            parentTypeName: String,
            insideChoice: Boolean = false,
            visitedGroups: MutableSet<String> = mutableSetOf(),
        ): List<PropertyDefinition> {
            if (particle.localName == "group") {
                val refName = particle.getAttribute("ref").ifBlank { null }
                    ?.let { if (':' in it) it.substringAfter(':') else it }
                    ?: return emptyList()
                if (!visitedGroups.add(refName)) return emptyList()
                val def = groupDefs[refName] ?: run {
                    warn("xs:group ref '$refName' not found; skipping")
                    return emptyList()
                }
                return contentParticleOf(def)
                    ?.let { collectElementProperties(it, parentTypeName, insideChoice, visitedGroups) }
                    ?: emptyList()
            }

            val choiceHere = insideChoice || particle.localName == "choice"
            val result = mutableListOf<PropertyDefinition>()
            val children = particle.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i) as? Element ?: continue
                if (child.namespaceURI != XSD_NS) continue
                when (child.localName) {
                    "element" -> {
                        val prop = buildElementProperty(child, parentTypeName)
                        result += if (choiceHere && prop.type !is TypeRef.ListOf) {
                            prop.copy(nullable = true)
                        } else {
                            prop
                        }
                    }
                    "sequence", "all", "choice", "group" ->
                        result += collectElementProperties(child, parentTypeName, choiceHere, visitedGroups)
                }
            }
            return result
        }

        /**
         * Direct xs:attribute children plus xs:attributeGroup refs, resolved
         * recursively ([visited] guards self/mutual references).
         */
        private fun collectAttributeProperties(
            parent: Element,
            visited: MutableSet<String> = mutableSetOf(),
        ): List<PropertyDefinition> {
            val result = mutableListOf<PropertyDefinition>()
            xsdChildren(parent, "attribute").forEach { result += buildAttributeProperty(it) }
            for (ref in xsdChildren(parent, "attributeGroup")) {
                val refName = ref.getAttribute("ref").ifBlank { null }
                    ?.let { if (':' in it) it.substringAfter(':') else it }
                    ?: continue
                if (!visited.add(refName)) continue
                val def = attributeGroupDefs[refName] ?: run {
                    warn("xs:attributeGroup ref '$refName' not found; skipping")
                    null
                } ?: continue
                result += collectAttributeProperties(def, visited)
            }
            return result
        }

        /**
         * simpleContent: a typed text body plus attributes. When the base is an XSD
         * built-in, it becomes the contentProperty directly. When the base is another
         * (simpleContent) complex type — the UN/CEFACT qualified-type pattern — it is
         * recorded as superType and InheritanceFlattener inherits the contentProperty
         * and attributes.
         */
        private fun processSimpleContent(typeEl: Element, schemaName: String, simpleContent: Element) {
            val derivation = xsdChild(simpleContent, "extension")
                ?: xsdChild(simpleContent, "restriction")
                ?: return
            val baseRef = resolveTypeRef(derivation.getAttribute("base"), derivation)
            val attributes = collectAttributeProperties(derivation)

            val (contentProperty, superType) = when (baseRef) {
                is TypeRef.Primitive -> PropertyDefinition(
                    schemaName = "value",
                    kotlinName = "value",
                    type = baseRef,
                    nullable = false,
                    defaultValue = null,
                    documentation = null,
                    kind = PropertyKind.CONTENT,
                ) to null
                else -> null to baseRef
            }

            types += TypeDefinition.ComplexType(
                schemaName = schemaName,
                kotlinName = toPascalCase(schemaName),
                documentation = extractTypeDoc(typeEl),
                properties = attributes,
                contentProperty = contentProperty,
                superType = superType,
            )
        }

        /**
         * complexContent: xs:extension inherits the base's particles (resolved by
         * InheritanceFlattener — Kotlin data classes are final, so inheritance is
         * flattened, with superType kept as provenance). xs:restriction re-declares
         * the kept subset of the base's content, so its own particle list is already
         * the complete declaration.
         */
        private fun processComplexContent(typeEl: Element, schemaName: String, complexContent: Element) {
            val extension = xsdChild(complexContent, "extension")
            val restriction = xsdChild(complexContent, "restriction")
            val derivation = extension ?: restriction ?: return
            val base = derivation.getAttribute("base").ifBlank { null }
            // Only extension inherits; restriction's own declaration is complete.
            val superType = if (extension != null) base?.let { resolveTypeRef(it, derivation) } else null

            val properties = mutableListOf<PropertyDefinition>()
            contentParticleOf(derivation)?.let { properties += collectElementProperties(it, schemaName) }
            properties += collectAttributeProperties(derivation)
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
            val refAttr = el.getAttribute("ref").ifBlank { null }
            val name = el.getAttribute("name").ifBlank { null }
                ?: refAttr?.let { if (':' in it) it.substringAfter(':') else it }
                ?: ""
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
                refAttr != null -> resolveElementRef(refAttr)
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
                defaultValue = defaultLiteral(el, if (isList) TypeRef.ListOf(elementTypeRef) else elementTypeRef, name),
                documentation = extractTypeDoc(el),
                fixedValue = fixedLiteral(el, if (isList) TypeRef.ListOf(elementTypeRef) else elementTypeRef, name),
            )
        }

        /**
         * xs:element ref="q:name": the property is named by the referenced top-level
         * element; its type comes from that declaration's type attribute, or from
         * the named type generated for its inline complexType/simpleType.
         */
        private fun resolveElementRef(refAttr: String): TypeRef {
            val local = if (':' in refAttr) refAttr.substringAfter(':') else refAttr
            val target = elementDefs[local] ?: run {
                warn("xs:element ref '$refAttr' not found; typing as Any")
                return TypeRef.Primitive(PrimitiveType.ANY)
            }
            val typeAttr = target.getAttribute("type").ifBlank { null }
            return when {
                typeAttr != null -> resolveTypeRef(typeAttr, target)
                xsdChild(target, "complexType") != null || xsdChild(target, "simpleType") != null ->
                    TypeRef.Named(toPascalCase(local))
                else -> TypeRef.Primitive(PrimitiveType.ANY)
            }
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
                defaultValue = defaultLiteral(el, typeRef, name),
                documentation = extractTypeDoc(el),
                kind = PropertyKind.ATTRIBUTE,
                fixedValue = fixedLiteral(el, typeRef, name),
            )
        }

        private fun defaultLiteral(el: Element, typeRef: TypeRef, propertyName: String): String? {
            val value = el.getAttribute("default").ifBlank { null } ?: return null
            return kotlinLiteralForLexicalValue(value, typeRef, propertyName)
        }

        private fun fixedLiteral(el: Element, typeRef: TypeRef, propertyName: String): String? {
            val value = el.getAttribute("fixed").ifBlank { null } ?: return null
            return kotlinLiteralForLexicalValue(value, typeRef, propertyName)
        }

        private fun kotlinLiteralForLexicalValue(value: String, typeRef: TypeRef, propertyName: String): String? =
            when (typeRef) {
                is TypeRef.Primitive -> primitiveLiteral(value, typeRef.type)
                is TypeRef.Named -> quoteKotlinString(value)
                is TypeRef.ListOf, is TypeRef.MapOf -> {
                    warn("Skipping default/fixed value for non-scalar property '$propertyName'")
                    null
                }
            }

        private fun primitiveLiteral(value: String, type: PrimitiveType): String? = when (type) {
            PrimitiveType.STRING, PrimitiveType.URI -> quoteKotlinString(value)
            PrimitiveType.INT, PrimitiveType.LONG, PrimitiveType.DOUBLE -> value
            PrimitiveType.FLOAT -> "${value}f"
            PrimitiveType.BOOLEAN -> when (value) {
                "true", "1" -> "true"
                "false", "0" -> "false"
                else -> quoteKotlinString(value)
            }
            PrimitiveType.DECIMAL -> "java.math.BigDecimal(${quoteKotlinString(value)})"
            PrimitiveType.DATE -> "java.time.LocalDate.parse(${quoteKotlinString(value)})"
            PrimitiveType.DATE_TIME -> "java.time.OffsetDateTime.parse(${quoteKotlinString(value)})"
            PrimitiveType.DURATION -> "java.time.Duration.parse(${quoteKotlinString(value)})"
            PrimitiveType.BYTES, PrimitiveType.ANY -> {
                warn("Skipping default/fixed value for $type property")
                null
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
