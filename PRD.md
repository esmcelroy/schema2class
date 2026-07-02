# schema2class — Product Requirements Document

## Problem

The Kotlin ecosystem has a significant gap: **no maintained tool generates idiomatic Kotlin from XSD, and no tool of any kind covers both XSD and JSON Schema through one pipeline.** A domain survey (see `docs/domain-survey.md`, July 2026) confirmed the XSD side is an empty niche and identified one active competitor on the JSON Schema side:

| Tool | Language | Schema support | Kotlin output | Programmatic API | Maintained |
|---|---|---|---|---|---|
| JAXB / xjc | Java | XSD | No (Java) | Partial | Yes (Java-only forever) |
| KAXB | Kotlin | XSD | Yes | No | **Abandoned** (no releases) |
| schema-gen | Groovy | XSD | Partial | No | Dormant, untested |
| jsonschema2pojo | Java | JSON Schema | No (Java) | No (CLI/Maven/Gradle) | Yes |
| json-kotlin-schema-codegen | Kotlin | JSON Schema only | Yes | Yes | Yes (single maintainer) |
| quicktype | TypeScript | JSON Schema | Secondary target | No (Node CLI) | Yes |
| Fabrikt | Kotlin | OpenAPI 3 only | Yes | Yes | Yes |
| kotlinx.serialization | Kotlin | Runtime only | N/A (serialization, not gen) | N/A | Yes |

Developers who need to consume XSD contracts in Kotlin are forced to wrap xjc-generated Java (platform types, no data classes, no sealed hierarchies) or hand-write classes. On the JSON Schema side, `json-kotlin-schema-codegen` is credible but single-format; teams with mixed XML + JSON contracts need two disjoint toolchains with inconsistent output. Fabrikt proves the architecture (Kotlin-native, KotlinPoet, Gradle plugin) earns adoption — for OpenAPI. schema2class applies it to the two formats left unserved, unified on one IR.

## Vision

`schema2class` is a Kotlin-native library that parses XSD and JSON Schema documents and programmatically generates idiomatic Kotlin source code. It is designed to be:

- **Library-first**: embeddable in any JVM/KMP tooling, not just a CLI
- **Idiomatic Kotlin output**: data classes, sealed classes, enums, nullable types, value classes
- **Annotation-aware**: optionally emits `@Serializable` (kotlinx.serialization), Jackson, or annotation-free output
- **Multiplatform-ready**: core IR and codegen target Kotlin Multiplatform; parsers are JVM-only today (XSD/JSON parsing depends on JVM XML/JSON libs) but the architecture leaves room to expand
- **Composable**: schemas can be composed, referenced, and extended — including **across formats**: the shared IR makes it possible to type an XML envelope (XSD) whose elements carry embedded JSON payloads (JSON Schema) in one generation pass, something no existing tool can do (`schema2class-sqb`)

---

## Goals

1. Parse XSD 1.0/1.1 schemas into an intermediate representation (IR)
2. Parse JSON Schema (draft-07, draft-2019-09, draft-2020-12) into the same IR
3. Generate idiomatic Kotlin source files from the IR
4. Provide a first-class programmatic Kotlin API (not just a code generator you shell out to)
5. Ship a Gradle plugin for build-time code generation
6. Ship a CLI for one-off / scripted use
7. Open-source under Apache 2.0

## Version Targets

| | Version | Rationale |
|---|---|---|
| **Kotlin** | 1.9.x | Stable, widely deployed; K2/2.x migration is a tracked future upgrade |
| **JVM toolchain** | 21 | Compile with modern JDK for fast builds and toolchain access |
| **JVM bytecode target** | 17 | Consumers on Java 17 LTS can use the library without upgrading |
| **Gradle** | 8.x | Required for Configuration Cache and modern plugin APIs |

The gap between toolchain (21) and target (17) costs nothing: consumers get a library that runs on JVM 17+, and we compile it with JDK 21 to get faster incremental builds and modern toolchain resolution. A tracked issue exists for migrating to Kotlin 2.x once it is the clear ecosystem default.

## Non-Goals (v1)

- Generating Kotlin from OpenAPI/Swagger (future scope, schema layer would be reusable)
- Round-tripping generated classes back to schemas
- Generating code in languages other than Kotlin
- XSD 2.0
- Full JSON Schema vocabularies beyond the core (hyper-schema, etc.)
- Kotlin 2.x / K2 compiler (tracked for future upgrade)

## Format Extension Points (post-v1 roadmap)

**Cross-format composition (`schema2class-sqb`)** sits above this list: an XML envelope
(XSD-described) with embedded JSON payloads (JSON Schema-described) typed end-to-end in
one generation pass. It is a confirmed user need, is uniquely enabled by the shared IR,
and requires both format halves to stay first-class — XSD work is sequenced first, but
JSON Schema features are not deprioritized.

Remaining extension points, ranked by the July 2026 domain survey (`docs/domain-survey.md`):

1. **xmlutil annotation mode** — pdvrieze/xmlutil is the de-facto kotlinx.serialization
   XML format (KMP-ready). Emitting `@XmlSerialName`/`@XmlElement`/`@XmlValue` from the
   IR's `PropertyKind` makes XSD-sourced classes actually round-trip XML on multiplatform.
2. **WSDL types frontend** — extract the XSD embedded in `<wsdl:types>` and feed the
   existing parser. Typed SOAP payloads without generating service stubs (which remain
   out of scope). Today's alternative is JAXWS → Java.
3. **DTD / RELAX NG via trang** — do not write parsers for these; document (and
   optionally wrap in the CLI) a trang conversion step (DTD/RNG → XSD → schema2class).
4. **OpenAPI 3.1** — embeds JSON Schema 2020-12; our JSON Schema parser is the reuse
   path. Fabrikt owns OpenAPI 3.0 today; no head-on competition planned.

Not pursuing: Schematron (rule-based, not structural), WADL/XML Beans/Castor (dead ecosystems).

---

## Architecture

### Module layout

```
schema2class/
├── core/                    # IR definitions + codegen interfaces
│   └── src/
├── parser-xsd/              # XSD → IR (JVM, JDK built-in XML DOM — no external deps)
│   └── src/
├── parser-jsonschema/       # JSON Schema → IR (JVM, depends on Jackson)
│   └── src/
├── codegen-kotlin/          # IR → Kotlin source (pure Kotlin, KMP-ready)
│   └── src/
├── cli/                     # CLI wrapper (kotlinx.cli or Clikt)
│   └── src/
├── gradle-plugin/           # Gradle plugin for build integration
│   └── src/
└── docs/                    # Documentation site (mkdocs or dokka)
```

### Intermediate Representation (IR)

The IR is the central contract. Both parsers produce IR; the codegen consumes IR. This decouples schema format from output language and makes future parsers (OpenAPI, Avro, Protobuf) or future targets (TypeScript, Swift) possible.

```
SchemaModel (namespace, packageName, types, sourceFormat)
  └── TypeDefinition (sealed)
      ├── ComplexType       → data class
      │     properties, superType (xs:extension),
      │     contentProperty (xs:simpleContent text body, emitted first)
      ├── AliasType         → typealias (value class later); carries Constraints
      ├── EnumType          → enum class
      │     EnumValue keeps serializedValue (wire) + kotlinName (constant) separate,
      │     so non-identifier values like UNECE numeric codes stay round-trippable
      └── UnionType         → sealed class hierarchy of data class variants

  PropertyDefinition        → constructor property
      schemaName, kotlinName, TypeRef, nullable, defaultValue, constraints
  TypeRef (sealed)          → Named (cross-package capable) | Primitive | ListOf
  Constraint (sealed)       → MinLength, MaxLength, Pattern, Min/MaxValue, Min/MaxItems
```

### Codegen output examples

**XSD complex type → data class:**
```kotlin
// Input: XSD ComplexType "Address"
@Serializable
data class Address(
    val street: String,
    val city: String,
    val zip: String,
    val country: String? = null,
)
```

**JSON Schema oneOf → sealed class:**
```kotlin
// Input: JSON Schema oneOf [Cat, Dog]
@Serializable
sealed class Pet {
    @Serializable data class Cat(val indoor: Boolean) : Pet()
    @Serializable data class Dog(val breed: String) : Pet()
}
```

**XSD simpleType restriction (enum) → enum class:**
```kotlin
enum class Color { RED, GREEN, BLUE }
```

---

## Phased Delivery Plan

### Phase 1 — Foundation
- [x] Project scaffold (Gradle multi-module, Kotlin, publishing config)
- [x] Core IR data model
- [x] JSON Schema parser (draft-07 baseline; `allOf` follow-up tracked)
- [x] Kotlin codegen for data classes, enums, sealed classes, typealiases
- [ ] Unit test suite: schema → IR → Kotlin round-trip (parser and codegen each
      have unit tests; the dedicated round-trip suite is `schema2class-7oe`)

### Phase 2 — XSD Support
- [x] XSD parser (complex types, simple types, enumerations, sequences,
      attributes, simpleContent, inline anonymous types)
- [x] XSD-specific constraints (minOccurs/maxOccurs → List/nullable,
      use=required → non-null)
- [ ] `xs:choice` → UnionType (`schema2class-eq1`)
- [ ] Namespace → package mapping (`schema2class-7h1`)
- [ ] `xs:import` / `xs:include` multi-file resolution

### Phase 3 — Advanced Type Mapping
- [x] `oneOf`/`anyOf` → sealed class hierarchies (JSON Schema side)
- [x] `$ref` and `$defs` resolution — same-document and circular refs;
      external file refs still open (`schema2class-1so`)
- [ ] `xs:extension` / `xs:restriction` inheritance mapping — basic
      extension → superType done; full semantics are `schema2class-8kr`
- [ ] Value class generation for constrained simple types
- [ ] Default value emission — JSON Schema `default` done; XSD `default`/`fixed`
      attributes still open

### Phase 4 — Build Tooling
- [ ] Gradle plugin (`schema2classGenerate` task)
- [ ] CLI (`schema2class generate --input schema.xsd --output src/`)
- [ ] Source set wiring (generated sources added to compile classpath)

### Phase 5 — Quality & Ecosystem
- [ ] Annotation modes: kotlinx.serialization, Jackson, none
- [ ] Dokka API docs
- [ ] Integration tests (generate → compile → deserialize real documents)
- [ ] Publishing to Maven Central
- [ ] Documentation site

---

## Open Source

**License: Apache License 2.0**

Rationale:
- Standard for Kotlin ecosystem tooling (kotlinx libraries, Ktor, Exposed all use Apache 2.0)
- Permissive enough for commercial use without copyleft concerns
- Patent grant protects users
- Compatible with the JVM/Gradle ecosystem's norms

---

## Success Metrics (v1 launch)

- Correctly generates compilable Kotlin for 95%+ of XSD schemas encountered in practice (tested against public schemas: XHTML, Maven POM, Spring XML, etc.)
- Correctly generates compilable Kotlin for all JSON Schema draft-07 core vocabulary keywords
- Gradle plugin integrates with a standard Kotlin project in < 5 lines of config
- Generated data classes successfully serialize/deserialize real documents via kotlinx.serialization
- Published to Maven Central

---

## Open Questions

1. **Value classes**: Should constrained simple types (e.g. `xs:string` with `maxLength`) become `@JvmInline value class`? Ergonomic but adds boxing complexity. Tracked as `schema2class-5vw`.
2. ~~**Nullability strategy**~~ **Resolved**: nullability lives on `PropertyDefinition.nullable`, never on `TypeRef`. XSD `minOccurs=0` / `use="optional"` and JSON Schema absence-from-`required` all map to `nullable = true`, and codegen emits `= null` defaults for nullable properties.
3. **Kotlin package naming**: XSD namespaces (URIs) → Kotlin packages needs a configurable mapping strategy. Tracked as `schema2class-7h1`.
4. **Annotation conflicts**: A field might need both a kotlinx and a Jackson annotation; should we emit both or let the user pick a mode? Current lean: annotation mode is a single codegen option (`schema2class-9oo`, `schema2class-n0g`).
