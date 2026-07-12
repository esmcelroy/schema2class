# schema2class

schema2class generates idiomatic Kotlin source from XSD and JSON Schema through
one shared intermediate representation. It is built for JVM and Gradle projects
that need generated models without falling back to Java-first tools.

## What it does

- Parses XSD files, imported XSD graphs, WSDL `<types>`, and JSON Schema files.
- Generates Kotlin data classes, enums, sealed hierarchies, type aliases, and
  optional value classes.
- Emits optional serialization annotations for kotlinx.serialization, xmlutil,
  and Jackson.
- Works from the Gradle plugin, CLI, or programmatic Kotlin API.

## Start here

Use [Getting Started](getting-started.md) for a five-minute Gradle setup. Use
[Compatibility Matrix](compatibility.md) when evaluating a schema feature, and
[Configuration Reference](configuration.md) for every CLI and Gradle option.

The generated source is ordinary Kotlin. Keep it checked in only when your build
requires that; otherwise use the Gradle plugin's generated source directory and
`schema2classVerifyGenerated` to catch drift in CI.
