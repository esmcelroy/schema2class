package io.github.schema2class.gradle

import io.github.schema2class.codegen.kotlin.AnnotationMode
import io.github.schema2class.codegen.kotlin.KotlinCodegen
import io.github.schema2class.core.ir.SchemaModel
import io.github.schema2class.core.naming.NamespacePackageMapper
import io.github.schema2class.parser.jsonschema.JsonSchemaParser
import io.github.schema2class.parser.xsd.XsdParser
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class Schema2ClassGenerateTask : DefaultTask() {

    @get:Nested
    abstract val specs: ListProperty<SchemaSpec>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        group = "schema2class"
        description = "Generates Kotlin classes from the configured XSD and JSON Schema documents"
    }

    @TaskAction
    fun generate() {
        val outDir = outputDirectory.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        for (spec in specs.get()) {
            val source = spec.source.get().asFile
            val mode = parseAnnotationMode(spec)
            val codegen = KotlinCodegen(
                KotlinCodegen.Options(
                    annotationMode = mode,
                    generateValueClasses = spec.valueClasses.get(),
                ),
            )

            val models: List<SchemaModel> = when (source.extension.lowercase()) {
                "xsd" -> parseXsd(spec, source)
                "json" -> listOf(parseJsonSchema(spec, source))
                else -> throw GradleException(
                    "schema2class: cannot detect schema format of '${source.name}' " +
                        "(spec '${spec.name}'): expected a .xsd or .json file",
                )
            }

            for (model in models) {
                for ((relativePath, content) in codegen.generate(model)) {
                    File(outDir, relativePath).apply {
                        parentFile.mkdirs()
                        writeText(content)
                    }
                }
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

    private fun parseAnnotationMode(spec: SchemaSpec): AnnotationMode {
        val raw = spec.annotationMode.get()
        return try {
            AnnotationMode.valueOf(raw.uppercase().replace('-', '_'))
        } catch (e: IllegalArgumentException) {
            throw GradleException(
                "schema2class: spec '${spec.name}' has unknown annotationMode '$raw' " +
                    "(expected one of ${AnnotationMode.entries.joinToString()})",
            )
        }
    }
}
