package ca.esmcelroy.schema2class.gradle

import ca.esmcelroy.schema2class.codegen.kotlin.AnnotationMode
import ca.esmcelroy.schema2class.codegen.kotlin.KotlinCodegen
import ca.esmcelroy.schema2class.core.ir.SchemaModel
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
            ),
        )
        parseModels(spec).forEach { model -> writeModel(codegen, model, outDir) }
    }

    private fun parseModels(spec: SchemaSpec): List<SchemaModel> {
        val source = spec.source.get().asFile
        return when (source.extension.lowercase()) {
            "xsd" -> parseXsd(spec, source)
            "json" -> listOf(parseJsonSchema(spec, source))
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
            listOf(XsdParser().parse(source, explicitPackage))
        } else {
            val mapper = NamespacePackageMapper(overrides = spec.packageOverrides.get())
            XsdParser().parseWithImports(source, mapper)
        }
    }

    private fun parseJsonSchema(spec: SchemaSpec, source: File): SchemaModel {
        val packageName = spec.packageName.orNull
            ?: throw GradleException(
                "schema2class: spec '${spec.name}' is a JSON Schema and requires packageName",
            )
        return JsonSchemaParser().parse(source, packageName)
    }

    private fun parseWsdl(spec: SchemaSpec, source: File): List<SchemaModel> {
        val mapper = NamespacePackageMapper(
            basePackage = spec.packageName.orNull,
            overrides = spec.packageOverrides.get(),
        )
        return WsdlParser().parse(source, mapper)
    }

    private fun parseAnnotationMode(spec: SchemaSpec): AnnotationMode {
        val raw = spec.annotationMode.get()
        return try {
            AnnotationMode.valueOf(raw.uppercase().replace('-', '_'))
        } catch (e: IllegalArgumentException) {
            throw GradleException(
                "schema2class: spec '${spec.name}' has unknown annotationMode '$raw' " +
                    "(expected one of ${AnnotationMode.entries.joinToString()})",
                e,
            )
        }
    }
}
