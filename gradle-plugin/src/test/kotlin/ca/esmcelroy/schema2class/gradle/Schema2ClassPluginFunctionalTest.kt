package ca.esmcelroy.schema2class.gradle

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.assertions.throwables.shouldThrow
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Functional tests running the plugin in a real Gradle build via TestKit.
 * The mixed-format test is the acceptance case for schema2class-sqb: one
 * invocation processes an .xsd and a schema.json side by side.
 */
class Schema2ClassPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name").shouldNotBeNull().bufferedReader().readText()

    private fun writeProject(buildScript: String) {
        File(projectDir, "settings.gradle").writeText("rootProject.name = 'scratch'\n")
        File(projectDir, "build.gradle").writeText(buildScript)
        val schemas = File(projectDir, "schemas").apply { mkdirs() }
        File(schemas, "envelope.xsd").writeText(fixture("business-doc.xsd"))
        File(schemas, "payload.schema.json").writeText(fixture("telemetry-payload.schema.json"))
    }

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*args)
            .withPluginClasspath()

    // ── The schema2class-sqb acceptance case ─────────────────────────────────

    @Test
    fun `one invocation generates from an xsd and a json schema side by side`() {
        writeProject(
            """
            plugins { id 'ca.esmcelroy.schema2class' }

            schema2class {
                schemas {
                    envelope {
                        source = file('schemas/envelope.xsd')
                        annotationMode = 'XMLUTIL'
                        valueClasses = true
                    }
                    payload {
                        source = file('schemas/payload.schema.json')
                        packageName = 'com.corp.payload'
                        annotationMode = 'KOTLINX_SERIALIZATION'
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runner("schema2classGenerate").build()
        result.task(":schema2classGenerate")?.outcome shouldBe TaskOutcome.SUCCESS

        val outDir = File(projectDir, "build/generated/schema2class/kotlin")

        // XSD side: package derived from targetNamespace urn:test:business-doc
        val amount = File(outDir, "test/business_doc/AmountType.kt")
        amount.exists() shouldBe true
        amount.readText() shouldContain "@XmlSerialName"
        amount.readText() shouldContain "namespace = \"urn:test:business-doc\""
        val referenceId = File(outDir, "test/business_doc/ReferenceIdType.kt")
        referenceId.readText() shouldContain "value class ReferenceIdType"

        // JSON side: explicit package, kotlinx annotations
        val payload = File(outDir, "com/corp/payload/TelemetryPayload.kt")
        payload.exists() shouldBe true
        payload.readText() shouldContain "@Serializable"
        payload.readText() shouldContain "@SerialName(\"firmware-version\")"
    }

    @Test
    fun `second run is up to date`() {
        writeProject(
            """
            plugins { id 'ca.esmcelroy.schema2class' }

            schema2class {
                schemas {
                    envelope { source = file('schemas/envelope.xsd') }
                }
            }
            """.trimIndent(),
        )

        runner("schema2classGenerate").build()
        val second = runner("schema2classGenerate").build()
        second.task(":schema2classGenerate")?.outcome shouldBe TaskOutcome.UP_TO_DATE
    }

    @Test
    fun `explicit packageName on xsd overrides namespace derivation`() {
        writeProject(
            """
            plugins { id 'ca.esmcelroy.schema2class' }

            schema2class {
                schemas {
                    envelope {
                        source = file('schemas/envelope.xsd')
                        packageName = 'com.corp.envelope'
                    }
                }
            }
            """.trimIndent(),
        )

        runner("schema2classGenerate").build()
        File(projectDir, "build/generated/schema2class/kotlin/com/corp/envelope/AmountType.kt")
            .exists() shouldBe true
    }

    @Test
    fun `json schema without packageName fails with a clear message`() {
        writeProject(
            """
            plugins { id 'ca.esmcelroy.schema2class' }

            schema2class {
                schemas {
                    payload { source = file('schemas/payload.schema.json') }
                }
            }
            """.trimIndent(),
        )

        val result = runner("schema2classGenerate").buildAndFail()
        result.output shouldContain "requires packageName"
    }

    @Test
    fun `output directory outside build directory fails before cleanup`() {
        writeProject(
            """
            plugins { id 'ca.esmcelroy.schema2class' }

            schema2class {
                outputDirectory = layout.projectDirectory.dir('generated')
                schemas {
                    envelope { source = file('schemas/envelope.xsd') }
                }
            }
            """.trimIndent(),
        )
        val existingFile = File(projectDir, "generated/keep.txt").apply {
            parentFile.mkdirs()
            writeText("do not delete")
        }

        val result = runner("schema2classGenerate").buildAndFail()

        result.output shouldContain "outputDirectory must be inside the project build directory"
        existingFile.readText() shouldBe "do not delete"
    }

    @Test
    fun `task rejects output directory outside build directory before cleanup`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val task = project.tasks.register("generate", Schema2ClassGenerateTask::class.java).get()
        val existingFile = File(projectDir, "generated/keep.txt").apply {
            parentFile.mkdirs()
            writeText("do not delete")
        }
        task.outputDirectory.set(project.layout.projectDirectory.dir("generated"))
        task.specs.set(emptyList())

        val error = shouldThrow<GradleException> {
            task.generate()
        }

        error.message.shouldNotBeNull() shouldContain "outputDirectory must be inside the project build directory"
        existingFile.readText() shouldBe "do not delete"
    }

    @Test
    fun `task cleans output directory inside build directory`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val task = project.tasks.register("generate", Schema2ClassGenerateTask::class.java).get()
        val outputDir = File(projectDir, "build/generated/schema2class/kotlin")
        val staleFile = File(outputDir, "stale.txt").apply {
            parentFile.mkdirs()
            writeText("stale")
        }
        task.outputDirectory.set(project.layout.buildDirectory.dir("generated/schema2class/kotlin"))
        task.specs.set(emptyList())

        task.generate()

        outputDir.isDirectory shouldBe true
        staleFile.exists() shouldBe false
    }
}
