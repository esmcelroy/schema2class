# Configuration Reference

## Gradle plugin

```kotlin
schema2class {
    outputDirectory = layout.buildDirectory.dir("generated/schema2class/kotlin")
    verifyDirectory = outputDirectory

    schemas {
        create("payload") {
            source = file("schemas/payload.schema.json")
            packageName = "com.example.payload"
            annotationMode = "JACKSON"
            omitNulls = true
            enforceConstraints = true
            enumUnknownFallback = true
            valueClasses = false
            nameBindings = file("schemas/names.conf")
        }
    }
}
```

| Property | Type | Default | Applies to | Description |
|---|---|---|---|---|
| `outputDirectory` | Directory | `build/generated/schema2class/kotlin` | extension | Generated Kotlin output. |
| `verifyDirectory` | Directory | `outputDirectory` | extension | Directory compared by `schema2classVerifyGenerated`. |
| `source` | File | required | schema | `.xsd`, `.wsdl`, or `.json` schema file. |
| `packageName` | String | XSD: derived, JSON: required | schema | Explicit output package. For WSDL, used as a base package. |
| `annotationMode` | String | `NONE` | schema | `NONE`, `KOTLINX_SERIALIZATION`, `XMLUTIL`, or `JACKSON`; CLI shorthands `none`, `kotlinx`, `xmlutil`, and `jackson` are also accepted. |
| `valueClasses` | Boolean | `false` | schema | Emit constrained simple scalar types as `@JvmInline value class`. |
| `omitNulls` | Boolean | `false` | schema | Emit Jackson non-null inclusion metadata. |
| `enforceConstraints` | Boolean | `false` | schema | Emit generated `require` guards for supported constraints. |
| `enumUnknownFallback` | Boolean | `false` | schema | Emit Jackson integer enum `UNKNOWN` fallback support. |
| `packageOverrides` | Map | empty | XSD/WSDL | Namespace URI to Kotlin package override. |
| `wireNamespace` | String | unset | XSD | XML namespace to emit in XML annotations. |
| `wireNamespaceOverrides` | Map | empty | XSD | Schema namespace URI to XML wire namespace override. |
| `nameBindings` | File | unset | JSON Schema | Sidecar generated-name bindings file. |

## Tasks

| Task | Description |
|---|---|
| `schema2classGenerate` | Generates configured Kotlin sources. |
| `schema2classVerifyGenerated` | Regenerates to a temporary directory and fails when output differs from `verifyDirectory`. |

## CLI

```bash
schema2class generate \
  --input schemas/envelope.xsd \
  --input schemas/payload.schema.json=com.example.payload \
  --output build/generated/schema2class/kotlin \
  --annotation-mode jackson \
  --omit-nulls \
  --enforce-constraints \
  --enum-unknown-fallback
```

| Option | Description |
|---|---|
| `--input`, `-i` | Schema file, optionally with `=package`; repeatable. |
| `--output`, `-o` | Generated source directory. |
| `--annotation-mode`, `-a` | `none`, `kotlinx`, `xmlutil`, or `jackson`. |
| `--value-classes` | Emit constrained simple scalar types as value classes. |
| `--omit-nulls` | Emit supported null-omission metadata. |
| `--enforce-constraints` | Emit generated constraint guards. |
| `--enum-unknown-fallback` | Emit supported enum unknown fallback members. |
| `--package-override` | XSD namespace URI to package override; repeatable. |
| `--wire-namespace` | XML wire namespace when no namespace-specific override is present. |
| `--wire-namespace-override` | Schema namespace URI to XML wire namespace override; repeatable. |
| `--base-package` | Prefix for namespace-derived packages. |
| `--name-bindings` | JSON Schema naming binding file. |
