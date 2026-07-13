import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.Sign
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.plugin.publish) apply false
    jacoco
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val releaseGroup = providers.gradleProperty("GROUP").get()
val releaseVersion = providers.gradleProperty("VERSION_NAME").get()
val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
    providers.gradleProperty("signing.secretKeyRingFile").isPresent
val publishableProjects = setOf(
    "core",
    "parser-xsd",
    "parser-jsonschema",
    "codegen-kotlin",
    "cli",
    "gradle-plugin",
)

fun publishedArtifactId(projectName: String): String = "schema2class-$projectName"

repositories {
    mavenCentral()
}

group = releaseGroup
version = releaseVersion

extensions.configure<JacocoPluginExtension> {
    toolVersion = versionCatalog.findVersion("jacoco").get().requiredVersion
}

subprojects {
    group = releaseGroup
    version = releaseVersion

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

    tasks.withType<Sign>().configureEach {
        onlyIf("a signing key is configured") { hasSigningKey }
    }

    if (name in publishableProjects) {
        apply(plugin = "com.vanniktech.maven.publish")

        extensions.configure<BasePluginExtension> {
            archivesName.set(publishedArtifactId(project.name))
        }

        tasks.withType<Jar>().configureEach {
            metaInf {
                from(rootProject.file("LICENSE"))
            }
        }

        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()
            coordinates(releaseGroup, publishedArtifactId(project.name), releaseVersion)
            pom {
                name.set("schema2class ${project.name}")
                description.set("Kotlin-native XSD and JSON Schema code generation (${project.name})")
                inceptionYear.set("2026")
                url.set("https://github.com/esmcelroy/schema2class")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("esmcelroy")
                        name.set("Eric McElroy")
                        url.set("https://github.com/esmcelroy")
                    }
                }
                scm {
                    url.set("https://github.com/esmcelroy/schema2class")
                    connection.set("scm:git:https://github.com/esmcelroy/schema2class.git")
                    developerConnection.set("scm:git:ssh://git@github.com/esmcelroy/schema2class.git")
                }
            }
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
