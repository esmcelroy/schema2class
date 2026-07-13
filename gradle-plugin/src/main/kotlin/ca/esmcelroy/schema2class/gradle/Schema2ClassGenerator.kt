package ca.esmcelroy.schema2class.gradle

import ca.esmcelroy.schema2class.codegen.kotlin.AnnotationMode
import ca.esmcelroy.schema2class.codegen.kotlin.KotlinCodegen
import ca.esmcelroy.schema2class.core.ir.SchemaModel
import ca.esmcelroy.schema2class.core.naming.NamingBindings
import ca.esmcelroy.schema2class.core.naming.NamespacePackageMapper
import ca.esmcelroy.schema2class.parser.jsonschema.JsonSchemaParser
import ca.esmcelroy.schema2class.parser.xsd.WsdlParser
import ca.esmcelroy.schema2class.parser.xsd.XsdParser
import org.gradle.api.GradleException
import java.io.File

internal object Schema2ClassGenerator {
    fun generate(specs: Iterable<SchemaSpec>, outDir: File) {
        for (spec in specs) {
            generateSpec(spec, outDir)
        }
    }

    private fun generateSpec(spec: SchemaSpec, outDir: File) {
        val codegen = KotlinCodegen(
            KotlinCodegen.Options(
                annotationMode = parseAnnotationMode(spec),
                generateValueClasses = spec.valueClasses.get(),
                omitNulls = spec.omitNulls.get(),
                enforceConstraints = spec.enforceConstraints.get(),
                enumUnknownFallback = spec.enumUnknownFallback.get(),
            ),
        )
        parseModels(spec).forEach { model -> writeModel(codegen, model, outDir) }
    }

    private fun parseModels(spec: SchemaSpec): List<SchemaModel> {
        val source = spec.source.get().asFile
        return when (source.extension.lowercase()) {
            "xsd" -> parseXsd(spec, source)
            "json" -> parseJsonSchema(spec, source)
            "wsdl" -> parseWsdl(spec, source)
            else -> throw GradleException(
                "schema2class: cannot detect schema format of '${source.name}' " +
                    "(spec '${spec.name}'): expected a .xsd, .wsdl, or .json file",
            )
        }
    }

    private fun writeModel(codegen: KotlinCodegen, model: SchemaModel, outDir: File) {
        codegen.generate(model).forEach { (relativePath, content) ->
            File(outDir, relativePath).apply {
                parentFile.mkdirs()
                writeText(content)
            }
        }
    }

    private fun parseXsd(spec: SchemaSpec, source: File): List<SchemaModel> {
        val explicitPackage = spec.packageName.orNull
        return if (explicitPackage != null) {
            listOf(XsdParser().parse(source, explicitPackage, spec.wireNamespace.orNull))
        } else {
            val mapper = NamespacePackageMapper(overrides = spec.packageOverrides.get())
            XsdParser().parseWithImports(
                file = source,
                packageMapper = mapper,
                wireNamespaceOverrides = spec.wireNamespaceOverrides.get().toNullableKeyMap(),
                defaultWireNamespace = spec.wireNamespace.orNull,
            )
        }
    }

    private fun parseJsonSchema(spec: SchemaSpec, source: File): List<SchemaModel> {
        val packageName = spec.packageName.orNull
            ?: throw GradleException(
                "schema2class: spec '${spec.name}' is a JSON Schema and requires packageName",
            )
        return JsonSchemaParser(namingBindings = namingBindings(spec)).parseWithRefs(source, packageName)
    }

    private fun parseWsdl(spec: SchemaSpec, source: File): List<SchemaModel> {
        val mapper = NamespacePackageMapper(
            basePackage = spec.packageName.orNull,
            overrides = spec.packageOverrides.get(),
        )
        return try {
            WsdlParser().parse(source, mapper)
        } catch (e: IllegalArgumentException) {
            throw GradleException("schema2class: failed to parse WSDL '${source.name}': ${e.message}", e)
        }
    }

    private fun parseAnnotationMode(spec: SchemaSpec): AnnotationMode {
        val raw = spec.annotationMode.get()
        val normalized = raw.lowercase().replace('_', '-')
        return when (normalized) {
            "none" -> AnnotationMode.NONE
            "kotlinx", "kotlinx-serialization" -> AnnotationMode.KOTLINX_SERIALIZATION
            "xmlutil" -> AnnotationMode.XMLUTIL
            "jackson" -> AnnotationMode.JACKSON
            else -> throw GradleException(
                "schema2class: spec '${spec.name}' has unknown annotationMode '$raw' " +
                    "(expected none, kotlinx, xmlutil, jackson, or enum-style names)",
            )
        }
    }

    private fun namingBindings(spec: SchemaSpec): NamingBindings =
        spec.nameBindings.orNull?.asFile?.let(NamingBindings::fromFile) ?: NamingBindings.EMPTY

    private fun Map<String, String>.toNullableKeyMap(): Map<String?, String> =
        entries.associate { (key, value) -> (key as String?) to value }

}
