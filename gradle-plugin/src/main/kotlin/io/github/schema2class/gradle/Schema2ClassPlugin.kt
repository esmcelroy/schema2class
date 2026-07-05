package io.github.schema2class.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.JavaPluginExtension

class Schema2ClassPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("schema2class", Schema2ClassExtension::class.java)

        val generateTask = project.tasks.register(
            "schema2classGenerate",
            Schema2ClassGenerateTask::class.java,
        ) { task ->
            task.specs.set(project.provider { extension.schemas.toList() })
            task.outputDirectory.set(extension.outputDirectory)
        }

        // When the Kotlin JVM plugin is present, add the generated sources to the
        // main source set. The Kotlin plugin attaches a "kotlin" SourceDirectorySet
        // extension to each Java source set, so no compile-time dependency on the
        // Kotlin Gradle plugin is needed.
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            val sourceSets = project.extensions.getByType(JavaPluginExtension::class.java).sourceSets
            sourceSets.named("main") { main ->
                val kotlinSourceSet = main.extensions.getByName("kotlin") as SourceDirectorySet
                kotlinSourceSet.srcDir(generateTask.flatMap { it.outputDirectory })
            }
        }
    }
}
