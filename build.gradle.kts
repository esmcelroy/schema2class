import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    jacoco
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

repositories {
    mavenCentral()
}

extensions.configure<JacocoPluginExtension> {
    toolVersion = versionCatalog.findVersion("jacoco").get().requiredVersion
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "jacoco")
    // Parsers and codegen expose core IR types in their public APIs, so modules
    // need the `api` dependency configuration.
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "17"
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = versionCatalog.findVersion("jacoco").get().requiredVersion
    }

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            xml.required.set(true)
            html.required.set(true)
            txt.required.set(false)
            sarif.required.set(true)
            md.required.set(false)
        }
    }
}

tasks.register<JacocoReport>("jacocoRootReport") {
    group = "verification"
    description = "Generates an aggregate JaCoCo coverage report for all subprojects."

    dependsOn(subprojects.map { it.tasks.named("test") })

    executionData.from(
        subprojects.map { subproject ->
            subproject.fileTree(subproject.layout.buildDirectory) {
                include("jacoco/*.exec")
            }
        },
    )
    classDirectories.from(subprojects.map { it.layout.buildDirectory.dir("classes/kotlin/main") })
    sourceDirectories.from(subprojects.map { it.layout.projectDirectory.dir("src/main/kotlin") })

    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoRootReport/jacocoRootReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoRootReport/html"))
    }
}
