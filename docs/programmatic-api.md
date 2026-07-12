# Programmatic API

The library API is the same pipeline used by the CLI and Gradle plugin:

1. Parse a schema into `SchemaModel`.
2. Generate Kotlin source strings from that model.
3. Write the generated files where your build expects them.

## Parse XSD

```kotlin
import ca.esmcelroy.schema2class.core.naming.NamespacePackageMapper
import ca.esmcelroy.schema2class.parser.xsd.XsdParser
import java.io.File

val mapper = NamespacePackageMapper(
    basePackage = "com.example.generated",
    overrides = mapOf("urn:example:billing" to "com.example.billing"),
)

val models = XsdParser().parseWithImports(
    file = File("schemas/billing.xsd"),
    packageMapper = mapper,
)
```

Use `parse(file, packageName)` when you want a single XSD file generated into an
explicit package.

## Parse JSON Schema

```kotlin
import ca.esmcelroy.schema2class.parser.jsonschema.JsonSchemaParser
import java.io.File

val model = JsonSchemaParser().parse(
    file = File("schemas/payload.schema.json"),
    packageName = "com.example.payload",
)
```

For external `$ref` graphs, use `parseWithRefs` so shared documents are parsed
once and generated into stable packages.

## Generate Kotlin

```kotlin
import ca.esmcelroy.schema2class.codegen.kotlin.AnnotationMode
import ca.esmcelroy.schema2class.codegen.kotlin.KotlinCodegen

val codegen = KotlinCodegen(
    KotlinCodegen.Options(
        annotationMode = AnnotationMode.JACKSON,
        omitNulls = true,
        enforceConstraints = true,
        enumUnknownFallback = true,
    ),
)

val files: Map<String, String> = codegen.generate(model)
files.forEach { (relativePath, source) ->
    File("build/generated/schema2class/kotlin", relativePath)
        .apply { parentFile.mkdirs() }
        .writeText(source)
}
```

`relativePath` includes the package directory and Kotlin file name, such as
`com/example/payload/TelemetryPayload.kt`.

## Naming hooks

For JSON Schema, pass `NamingBindings` or a custom `NamingStrategy` to
`JsonSchemaParser` when wire names are terse but generated Kotlin names should be
friendly. See [Naming Bindings](naming-bindings.md).
