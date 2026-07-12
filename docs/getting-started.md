# Getting Started

This guide wires schema2class into a Kotlin JVM project and generates Kotlin
models before compilation.

## 1. Apply the plugin

```kotlin
plugins {
    kotlin("jvm") version "1.9.25"
    id("ca.esmcelroy.schema2class") version "<schema2class-version>"
}
```

## 2. Configure schemas

```kotlin
schema2class {
    schemas {
        create("envelope") {
            source = file("schemas/envelope.xsd")
            annotationMode = "XMLUTIL"
        }

        create("payload") {
            source = file("schemas/payload.schema.json")
            packageName = "com.example.payload"
            annotationMode = "KOTLINX_SERIALIZATION"
        }
    }
}
```

For XSD, `packageName` is optional. When it is omitted, schema2class derives a
package from `targetNamespace` and resolves `xs:include` / `xs:import`
transitively. For JSON Schema, `packageName` is required.

## 3. Generate and compile

```bash
./gradlew schema2classGenerate compileKotlin
```

The plugin adds `build/generated/schema2class/kotlin` to the main Kotlin source
set when the Kotlin JVM plugin is present.

## 4. Add a drift check

```bash
./gradlew schema2classVerifyGenerated
```

`schema2classVerifyGenerated` regenerates into a build-temp directory and fails
if the configured generated output differs. Use it in CI when generated sources
are checked in or when you want an explicit schema drift gate.

## CLI equivalent

```bash
schema2class generate \
  --input schemas/envelope.xsd \
  --input schemas/payload.schema.json=com.example.payload \
  --annotation-mode kotlinx \
  --output build/generated/schema2class/kotlin
```
