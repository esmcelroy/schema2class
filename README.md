# schema2class

schema2class is a Kotlin-native code generator for XSD and JSON Schema. It
parses schemas into a shared intermediate representation and emits idiomatic
Kotlin source: data classes, enums, sealed hierarchies, type aliases, optional
value classes, and serialization annotations.

## Why

Kotlin projects that consume XML contracts are usually forced through Java-first
tooling such as JAXB/xjc. schema2class generates Kotlin directly and handles
mixed XML + JSON contract sets through one CLI, one Gradle plugin, and one IR.

## Status

The core pipeline is implemented:

- XSD parser, including multi-file `xs:include` / `xs:import` resolution.
- WSDL `<types>` frontend for embedded XSD payload types.
- JSON Schema parser with `$ref` / `$defs`, naming bindings, defaults, and
  constraints.
- Kotlin codegen with `NONE`, `KOTLINX_SERIALIZATION`, `XMLUTIL`, and `JACKSON`
  annotation modes.
- CLI and Gradle plugin.
- Generated-source drift check.
- MkDocs documentation site.

The main remaining release task is publishing to Maven Central.

## Quick Start

Gradle plugin configuration:

```kotlin
plugins {
    kotlin("jvm") version "1.9.25"
    id("ca.esmcelroy.schema2class") version "<schema2class-version>"
}

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

Run:

```bash
./gradlew schema2classGenerate compileKotlin
```

CLI equivalent:

```bash
schema2class generate \
  --input schemas/envelope.xsd \
  --input schemas/payload.schema.json=com.example.payload \
  --annotation-mode kotlinx \
  --output build/generated/schema2class/kotlin
```

## Documentation

- Documentation site: <http://esmcelroy.ca/schema2class/>
- Getting started: [docs/getting-started.md](docs/getting-started.md)
- Configuration reference: [docs/configuration.md](docs/configuration.md)
- Compatibility matrix: [docs/compatibility.md](docs/compatibility.md)
- Product plan: [PRD.md](PRD.md)

## Repository Layout

```text
core/                 IR and shared naming/package utilities
parser-xsd/           XSD and WSDL frontend
parser-jsonschema/    JSON Schema frontend
codegen-kotlin/       KotlinPoet code generator
cli/                  schema2class command-line interface
gradle-plugin/        Gradle plugin and generated-source verification task
integration-tests/    generate -> compile -> serialize/deserialize tests
docs/                 MkDocs documentation
```

## Development

Use JDK 21. In this local repo, Java is provided through SDKMAN:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && ./gradlew test detekt jacocoRootReport
```

Docs:

```bash
pip install -r requirements-docs.txt
mkdocs serve
mkdocs build --strict
```

Work is tracked in Beads:

```bash
bd ready
bd show <id>
bd update <id> --claim
bd close <id>
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
