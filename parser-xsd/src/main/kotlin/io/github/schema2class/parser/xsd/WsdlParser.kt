package io.github.schema2class.parser.xsd

import io.github.schema2class.core.ir.SchemaModel
import io.github.schema2class.core.naming.NamespacePackageMapper
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream

private const val WSDL_NS = "http://schemas.xmlsoap.org/wsdl/"
private const val WSDL20_NS = "http://www.w3.org/ns/wsdl"

/**
 * Thin WSDL frontend: extracts xs:schema children from wsdl:types and delegates
 * to [XsdParser]. Service stubs, bindings, operations, and SOAP metadata are out
 * of scope; generated models describe payload schema types only.
 */
class WsdlParser(
    private val xsdParser: XsdParser = XsdParser(),
) {
    fun parse(inputStream: InputStream, packageMapper: NamespacePackageMapper = NamespacePackageMapper()): List<SchemaModel> {
        val root = parseDocument(inputStream)
        val schemas = schemaRoots(root)
        require(schemas.isNotEmpty()) { "WSDL document contains no xs:schema children under wsdl:types" }
        return xsdParser.parseSchemaRoots(schemas, packageMapper)
    }

    fun parse(file: File, packageMapper: NamespacePackageMapper = NamespacePackageMapper()): List<SchemaModel> =
        file.inputStream().use { parse(it, packageMapper) }

    private fun parseDocument(inputStream: InputStream): Element {
        return SecureXml.parseDocument(inputStream)
    }

    private fun schemaRoots(root: Element): List<Element> {
        val result = mutableListOf<Element>()
        val types = root.getElementsByTagNameNS("*", "types")
        for (i in 0 until types.length) {
            val typesEl = types.item(i) as? Element ?: continue
            if (typesEl.namespaceURI != WSDL_NS && typesEl.namespaceURI != WSDL20_NS) continue
            val children = typesEl.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j) as? Element ?: continue
                if (child.namespaceURI == XSD_NS && child.localName == "schema") {
                    result += child
                }
            }
        }
        return result
    }
}
