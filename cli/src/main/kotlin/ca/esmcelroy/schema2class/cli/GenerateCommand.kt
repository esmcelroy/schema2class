package ca.esmcelroy.schema2class.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.associate
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import ca.esmcelroy.schema2class.codegen.kotlin.AnnotationMode
import ca.esmcelroy.schema2class.codegen.kotlin.KotlinCodegen
import ca.esmcelroy.schema2class.core.ir.SchemaModel
import ca.esmcelroy.schema2class.core.naming.NamingBindings
import ca.esmcelroy.schema2class.core.naming.NamespacePackageMapper
import ca.esmcelroy.schema2class.parser.jsonschema.JsonSchemaParser
import ca.esmcelroy.schema2class.parser.xsd.WsdlParser
import ca.esmcelroy.schema2class.parser.xsd.XsdParser
import java.io.File

/** One `--input` value: a schema file, optionally with `=package` appended. */
data class SchemaInput(val file: File, val packageName: String?)

class GenerateCommand : CliktCommand(
    name = "generate",
    help = """
        Generate Kotlin classes from one or more schema documents.

        Formats are detected from file extensions (.xsd, .json) and can be mixed
        in a single invocation. Append `=com.your.pkg` to an --input to pin its
        package. JSON Schema inputs require a package; XSD inputs without one
        derive packages from targetNamespace(s) and resolve xs:import/xs:include
        transitively.
    """.trimIndent(),
) {
    private val inputs: List<SchemaInput> by option(
        "--input", "-i",
        metavar = "FILE[=PACKAGE]",
        help = "Schema file, optionally with =package (repeatable)",
    ).convert { raw ->
        val path = raw.substringBefore('=')
        val pkg = raw.substringAfter('=', missingDelimiterValue = "").ifBlank { null }
        SchemaInput(File(path), pkg)
    }.multiple(required = true)

    private val output: File by option(
        "--output", "-o",
        help = "Directory for generated sources",
    ).file(canBeFile = false).default(File("generated"))

    private val annotationMode: AnnotationMode by option(
        "--annotation-mode", "-a",
        help = "Serialization annotations to emit",
    ).choice(
        "none" to AnnotationMode.NONE,
        "kotlinx" to AnnotationMode.KOTLINX_SERIALIZATION,
        "xmlutil" to AnnotationMode.XMLUTIL,
        "jackson" to AnnotationMode.JACKSON,
    ).default(AnnotationMode.NONE)

    private val valueClasses: Boolean by option(
        "--value-classes",
        help = "Generate @JvmInline value classes for constrained simple types instead of typealiases",
    ).flag(default = false)

    private val omitNulls: Boolean by option(
        "--omit-nulls",
        help = "Emit serializer metadata that omits null optional values when supported by the annotation mode",
    ).flag(default = false)

    private val enforceConstraints: Boolean by option(
        "--enforce-constraints",
        help = "Emit init require guards for supported schema constraints",
    ).flag(default = false)

    private val enumUnknownFallback: Boolean by option(
        "--enum-unknown-fallback",
        help = "Emit synthetic UNKNOWN enum members for supported annotation modes",
    ).flag(default = false)

    private val packageOverrides: Map<String, String> by option(
        "--package-override",
        metavar = "NAMESPACE=PACKAGE",
        help = "XSD namespace URI to Kotlin package override (repeatable)",
    ).associate()

    private val wireNamespace: String? by option(
        "--wire-namespace",
        help = "XML wire namespace for XSD models when no namespace-specific override is present",
    )

    private val wireNamespaceOverrides: Map<String, String> by option(
        "--wire-namespace-override",
        metavar = "SCHEMA_NAMESPACE=WIRE_NAMESPACE",
        help = "XSD schema namespace URI to XML wire namespace override (repeatable)",
    ).associate()

    private val basePackage: String? by option(
        "--base-package",
        help = "Prefix prepended to every namespace-derived package",
    )

    private val nameBindings: File? by option(
        "--name-bindings",
        help = "External naming binding file: Type.property=name or Type=FriendlyType",
    ).file(mustExist = true, canBeDir = false)

    private val namingBindings: NamingBindings by lazy {
        nameBindings?.let(NamingBindings::fromFile) ?: NamingBindings.EMPTY
    }

    override fun run() {
        val codegen = KotlinCodegen(
            KotlinCodegen.Options(
                annotationMode = annotationMode,
                generateValueClasses = valueClasses,
                omitNulls = omitNulls,
                enforceConstraints = enforceConstraints,
                enumUnknownFallback = enumUnknownFallback,
            ),
        )
        val fileCount = inputs.sumOf { generate(input = it, codegen = codegen) }

        echo("schema2class: generated $fileCount file(s) into $output")
    }

    private fun generate(input: SchemaInput, codegen: KotlinCodegen): Int {
        requireInputFile(input)
        return parseModels(input).sumOf { writeModel(it, codegen) }
    }

    private fun requireInputFile(input: SchemaInput) {
        if (!input.file.isFile) {
            throw UsageError("schema file not found: ${input.file}")
        }
    }

    private fun parseModels(input: SchemaInput): List<SchemaModel> =
        when (input.file.extension.lowercase()) {
            "xsd" -> parseXsd(input)
            "json" -> parseJsonSchema(input)
            "wsdl" -> parseWsdl(input)
            "dtd", "rng", "rnc" -> throw UsageError(
                "schema2class does not parse ${input.file.extension} directly; " +
                    "convert it to XSD with trang first (see docs/trang-conversion.md)",
            )
            else -> throw UsageError(
                "cannot detect schema format of '${input.file.name}': expected .xsd, .wsdl, or .json",
            )
        }

    private fun writeModel(model: SchemaModel, codegen: KotlinCodegen): Int =
        codegen.generate(model).onEach { (relativePath, content) ->
            File(output, relativePath).apply {
                parentFile.mkdirs()
                writeText(content)
            }
        }.size

    private fun parseXsd(input: SchemaInput): List<SchemaModel> =
        if (input.packageName != null) {
            listOf(XsdParser().parse(input.file, input.packageName, wireNamespace))
        } else {
            val mapper = NamespacePackageMapper(
                basePackage = basePackage,
                overrides = packageOverrides,
            )
            XsdParser().parseWithImports(
                file = input.file,
                packageMapper = mapper,
                wireNamespaceOverrides = wireNamespaceOverrides.toNullableKeyMap(),
                defaultWireNamespace = wireNamespace,
            )
        }

    private fun parseJsonSchema(input: SchemaInput): List<SchemaModel> {
        val packageName = input.packageName
            ?: throw UsageError(
                "JSON Schema input '${input.file.name}' requires a package: " +
                    "--input ${input.file}=com.example.generated",
            )
        return JsonSchemaParser(namingBindings = namingBindings).parseWithRefs(input.file, packageName)
    }

    @Suppress("SwallowedException")
    private fun parseWsdl(input: SchemaInput): List<SchemaModel> {
        val mapper = NamespacePackageMapper(
            basePackage = input.packageName ?: basePackage,
            overrides = packageOverrides,
        )
        return try {
            WsdlParser().parse(input.file, mapper)
        } catch (e: IllegalArgumentException) {
            throw UsageError("failed to parse WSDL '${input.file.name}': ${e.message}")
        }
    }

    private fun Map<String, String>.toNullableKeyMap(): Map<String?, String> =
        entries.associate { (key, value) -> (key as String?) to value }

}
