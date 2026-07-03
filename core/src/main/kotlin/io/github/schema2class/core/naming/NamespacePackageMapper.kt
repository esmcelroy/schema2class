package io.github.schema2class.core.naming

/**
 * Maps schema namespaces (XSD targetNamespace, JSON Schema $id — both URIs) to Kotlin
 * package names.
 *
 * Derivation follows the JAXB convention, adapted for Kotlin:
 *  - `http(s)://` URIs: host labels are reversed (dropping a leading `www.`), then path
 *    segments are appended. `http://maven.apache.org/POM/4.0.0` → `org.apache.maven.pom._4_0_0`
 *  - `urn:` URIs: colon-separated segments in order, no reversal.
 *    `urn:un:unece:uncefact:...` → `un.unece.uncefact...`
 *  - Every segment is lowercased; characters outside `[a-z0-9]` become `_`; segments
 *    starting with a digit get a `_` prefix; Kotlin hard keywords get a `_` suffix;
 *    a trailing `.xsd`/`.json`/`.wsdl` file extension on the last segment is dropped.
 *
 * [overrides] map exact namespace URIs to packages and are used verbatim (no [basePackage]
 * prefix). [basePackage] is prepended to every derived package. A null/blank namespace maps
 * to [basePackage] if set, else [defaultPackage].
 *
 * [toPackages] guarantees injectivity across a set of namespaces: when two distinct URIs
 * derive the same package, later ones (in iteration order) get `_2`, `_3`, … suffixes.
 */
class NamespacePackageMapper(
    private val basePackage: String? = null,
    private val overrides: Map<String, String> = emptyMap(),
    private val defaultPackage: String = "generated",
) {

    fun toPackage(namespace: String?): String {
        if (namespace.isNullOrBlank()) return basePackage ?: defaultPackage
        overrides[namespace]?.let { return it }
        val derived = derive(namespace)
        return when {
            derived.isEmpty() -> basePackage ?: defaultPackage
            basePackage != null -> "$basePackage.$derived"
            else -> derived
        }
    }

    /** Collision-safe mapping for a set of namespaces (e.g. a multi-file XSD import graph). */
    fun toPackages(namespaces: Collection<String?>): Map<String?, String> {
        val result = LinkedHashMap<String?, String>()
        val used = mutableSetOf<String>()
        for (ns in namespaces.distinct()) {
            val base = toPackage(ns)
            var candidate = base
            var counter = 2
            while (candidate in used) {
                candidate = "${base}_${counter++}"
            }
            used += candidate
            result[ns] = candidate
        }
        return result
    }

    private fun derive(uri: String): String {
        val segments: List<String> = when {
            uri.startsWith("urn:", ignoreCase = true) ->
                uri.substring(4).split(':')
            "://" in uri -> {
                val afterScheme = uri.substringAfter("://")
                val authority = afterScheme.substringBefore('/')
                val path = afterScheme.substringAfter('/', missingDelimiterValue = "")
                val host = authority.substringBefore(':').removePrefix("www.")
                host.split('.').reversed() + path.split('/')
            }
            else -> uri.split(':', '/')
        }

        val cleaned = segments
            .filter { it.isNotBlank() }
            .toMutableList()
        if (cleaned.isNotEmpty()) {
            cleaned[cleaned.lastIndex] = cleaned.last()
                .replace(Regex("\\.(xsd|json|wsdl)$", RegexOption.IGNORE_CASE), "")
        }

        return cleaned
            .map { sanitizeSegment(it) }
            .filter { it.isNotEmpty() }
            .joinToString(".")
    }

    private fun sanitizeSegment(segment: String): String {
        var s = segment.lowercase().replace(Regex("[^a-z0-9]"), "_")
        s = s.trim('_').ifEmpty { return "" }
        if (s.first().isDigit()) s = "_$s"
        if (s in KOTLIN_HARD_KEYWORDS) s = "${s}_"
        return s
    }

    private companion object {
        val KOTLIN_HARD_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
            "if", "in", "interface", "is", "null", "object", "package", "return",
            "super", "this", "throw", "true", "try", "typealias", "typeof",
            "val", "var", "when", "while",
        )
    }
}
