package ca.esmcelroy.schema2class.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class Schema2ClassGenerateTask : DefaultTask() {

    @get:Nested
    abstract val specs: ListProperty<SchemaSpec>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getSchemaDependencyFiles(): FileTree = schemaDependencyFileTree()

    init {
        group = "schema2class"
        description = "Generates Kotlin classes from the configured XSD and JSON Schema documents"
    }

    @TaskAction
    fun generate() {
        val outDir = prepareOutputDirectory(outputDirectory.get().asFile)
        Schema2ClassGenerator.generate(specs.get(), outDir)
    }

    private fun prepareOutputDirectory(outDir: File): File {
        val buildDir = project.layout.buildDirectory.get().asFile.canonicalFile
        val canonicalOutDir = outDir.canonicalFile
        val buildDirPrefix = buildDir.path + File.separator

        if (canonicalOutDir == buildDir || !canonicalOutDir.path.startsWith(buildDirPrefix)) {
            throw GradleException(
                "schema2class: outputDirectory must be inside the project build directory " +
                    "so generated files can be cleaned safely: $outDir",
            )
        }

        canonicalOutDir.deleteRecursively()
        canonicalOutDir.mkdirs()
        return canonicalOutDir
    }

    private fun schemaDependencyFileTree(): FileTree =
        project.files(
            specs.get().mapNotNull { spec ->
                spec.source.orNull?.asFile?.parentFile
            },
        ).asFileTree.matching { patterns ->
            patterns.include("**/*.xsd")
            patterns.include("**/*.wsdl")
            patterns.include("**/*.json")
            patterns.include("**/*.schema")
            patterns.include("**/*.schema.json")
            patterns.include("**/*.conf")
        }

}
