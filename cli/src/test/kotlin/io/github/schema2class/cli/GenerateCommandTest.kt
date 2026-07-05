package io.github.schema2class.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GenerateCommandTest {

    @TempDir
    lateinit var workDir: File

    private fun fixture(name: String): File {
        val target = File(workDir, name)
        javaClass.getResourceAsStream("/$name").shouldNotBeNull().use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    private fun cli() = Schema2ClassCli().subcommands(GenerateCommand())

    @Test
    fun `mixed xsd and json inputs generate in one invocation`() {
        val xsd = fixture("business-doc.xsd")
        val json = fixture("telemetry-payload.schema.json")
        val out = File(workDir, "out")

        val result = cli().test(
            "generate -i ${xsd.path} -i ${json.path}=com.corp.payload " +
                "-o ${out.path} --annotation-mode kotlinx",
        )

        result.statusCode shouldBe 0
        result.stdout shouldContain "generated"

        // XSD package derived from targetNamespace
        File(out, "test/business_doc/AmountType.kt").exists() shouldBe true
        // JSON package pinned via =package
        val payload = File(out, "com/corp/payload/TelemetryPayload.kt")
        payload.exists() shouldBe true
        payload.readText() shouldContain "@Serializable"
    }

    @Test
    fun `xmlutil mode emits xml annotations`() {
        val xsd = fixture("business-doc.xsd")
        val out = File(workDir, "out")

        val result = cli().test("generate -i ${xsd.path} -o ${out.path} -a xmlutil")

        result.statusCode shouldBe 0
        File(out, "test/business_doc/TextType.kt").readText() shouldContain "@XmlValue"
    }

    @Test
    fun `package override redirects a namespace`() {
        val xsd = fixture("business-doc.xsd")
        val out = File(workDir, "out")

        val result = cli().test(
            "generate -i ${xsd.path} -o ${out.path} " +
                "--package-override urn:test:business-doc=com.override.biz",
        )

        result.statusCode shouldBe 0
        File(out, "com/override/biz/InvoiceType.kt").exists() shouldBe true
    }

    @Test
    fun `json input without package fails with guidance`() {
        val json = fixture("telemetry-payload.schema.json")

        val result = cli().test("generate -i ${json.path} -o ${File(workDir, "out").path}")

        result.statusCode shouldBe 1
        result.stderr shouldContain "requires a package"
    }

    @Test
    fun `unknown extension fails clearly`() {
        val bogus = File(workDir, "schema.yaml").apply { writeText("nope") }

        val result = cli().test("generate -i ${bogus.path} -o ${File(workDir, "out").path}")

        result.statusCode shouldBe 1
        result.stderr shouldContain "expected .xsd or .json"
    }

    @Test
    fun `missing file fails clearly`() {
        val result = cli().test(
            "generate -i ${File(workDir, "absent.xsd").path} -o ${File(workDir, "out").path}",
        )

        result.statusCode shouldBe 1
        result.stderr shouldContain "not found"
    }
}
