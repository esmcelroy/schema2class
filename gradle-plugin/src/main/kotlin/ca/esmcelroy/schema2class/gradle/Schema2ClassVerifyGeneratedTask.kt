package ca.esmcelroy.schema2class.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class Schema2ClassVerifyGeneratedTask : DefaultTask() {

    @get:Nested
    abstract val specs: ListProperty<SchemaSpec>

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val verifyDirectory: DirectoryProperty

    @get:Internal
    abstract val workDirectory: DirectoryProperty

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getSchemaDependencyFiles(): FileTree = schemaDependencyFileTree()

    init {
        group = "schema2class"
        description = "Verifies that checked-in schema2class generated sources are current"
    }

    @TaskAction
    fun verifyGenerated() {
        val expectedDir = prepareWorkDirectory(workDirectory.get().asFile)
        Schema2ClassGenerator.generate(specs.get(), expectedDir)

        val actualDir = verifyDirectory.get().asFile
        val differences = compareDirectories(expectedDir, actualDir)
        if (differences.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("schema2class: generated sources are out of date")
                    appendLine("Expected generated output to match: $actualDir")
                    appendLine("Run schema2classGenerate or update checked-in generated sources.")
                    appendLine("Differences:")
                    differences.take(MAX_DIFFERENCES).forEach { appendLine("  $it") }
                    if (differences.size > MAX_DIFFERENCES) {
                        appendLine("  ... and ${differences.size - MAX_DIFFERENCES} more")
                    }
                },
            )
        }
    }

    private fun prepareWorkDirectory(dir: File): File {
        val buildDir = project.layout.buildDirectory.get().asFile.canonicalFile
        val canonicalDir = dir.canonicalFile
        val buildDirPrefix = buildDir.path + File.separator
        if (canonicalDir == buildDir || !canonicalDir.path.startsWith(buildDirPrefix)) {
            throw GradleException(
                "schema2class: verify workDirectory must be inside the project build directory: $dir",
            )
        }
        canonicalDir.deleteRecursively()
        canonicalDir.mkdirs()
        return canonicalDir
    }

    private fun compareDirectories(expectedDir: File, actualDir: File): List<String> {
        val expected = relativeFiles(expectedDir)
        val actual = relativeFiles(actualDir)
        val paths = (expected.keys + actual.keys).toSortedSet()
        return paths.mapNotNull { path ->
            val expectedFile = expected[path]
            val actualFile = actual[path]
            when {
                expectedFile == null -> "extra file: $path"
                actualFile == null -> "missing file: $path"
                !expectedFile.readBytes().contentEquals(actualFile.readBytes()) -> "changed file: $path"
                else -> null
            }
        }
    }

    private fun relativeFiles(root: File): Map<String, File> {
        if (!root.exists()) return emptyMap()
        if (!root.isDirectory) {
            throw GradleException("schema2class: verifyDirectory is not a directory: $root")
        }
        return root.walkTopDown()
            .filter { it.isFile }
            .associateBy { root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') }
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

    private companion object {
        const val MAX_DIFFERENCES = 50
    }
}
