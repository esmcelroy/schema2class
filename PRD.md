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
- **Composable**: schemas can be composed, referenced, and extended. Mixed-format projects are first-class: one build can process an `.xsd` and a `schema.json` side by side — e.g. a format where an XML document (XSD-described) has an element whose text content is JSON (JSON Schema-described) — generating envelope and payload classes together (`schema2class-sqb`)

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

**Mixed-format projects (`schema2class-sqb`)** sit above this list: a confirmed user
workflow pairs an `.xsd` (XML envelope) with a `schema.json` (payload carried as JSON
text inside one XML element). This needs no IR-level cross-format linkage — the
JSON-bearing element stays a `String` and the payload class is generated from its own
schema — but it does require the Gradle plugin and CLI to accept both formats in one
invocation with per-schema package configuration. It also keeps both format halves
first-class: XSD work is sequenced first, but JSON Schema is not deprioritized.

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
- [x] Unit test suites: schema → IR → Kotlin round-trip for both formats
      (`XsdRoundTripTest`, `JsonSchemaRoundTripTest`; real-world fixtures vendored)

### Phase 2 — XSD Support
- [x] XSD parser (complex types, simple types, enumerations, sequences,
      attributes, simpleContent, inline anonymous types)
- [x] XSD-specific constraints (minOccurs/maxOccurs → List/nullable,
      use=required → non-null)
- [x] `xs:choice` → UnionType (whole-content choices; nested/attributed choices
      flatten to nullable properties), `xs:group`/`xs:attributeGroup`, element refs
- [x] Namespace → package mapping (`NamespacePackageMapper`, see `docs/namespace-mapping.md`)
- [x] `xs:import` / `xs:include` multi-file resolution (`parseWithImports`, one model per namespace)

### Phase 3 — Advanced Type Mapping
- [x] `oneOf`/`anyOf` → sealed class hierarchies (JSON Schema side)
- [x] `$ref` and `$defs` resolution — same-document, circular, and external
      file refs (`parseWithRefs`: one model per document, shared types
      generated once, cross-document cycles safe)
- [x] `xs:extension` / `xs:restriction` inheritance mapping — flattened by
      `InheritanceFlattener` (data classes are final); restriction re-declares
      kept content; cross-namespace chains resolved over the whole schema set
- [ ] Value class generation for constrained simple types
- [x] Default/fixed value emission — JSON Schema `default`/`const` and XSD
      `default`/`fixed` become Kotlin defaults; fixed values emit guards

### Phase 4 — Build Tooling
- [x] Gradle plugin (`schema2classGenerate` task; mixed .xsd/.json specs with
      per-schema package + annotation mode — see `docs/mixed-format-projects.md`)
- [x] CLI (`schema2class generate -i schema.xsd -i payload.json=com.pkg -o out/`;
      mixed formats, per-input packages, namespace overrides, annotation modes)
- [x] Source set wiring (generated dir added to the main Kotlin source set when
      the Kotlin JVM plugin is present)
- [x] Generated-source drift check (`schema2classVerifyGenerated` compares
      configured generated sources against a fresh build-temp generation)

### Phase 5 — Quality & Ecosystem
- [x] Annotation modes: none, kotlinx.serialization, xmlutil (XML) — Jackson
      remains (`schema2class-n0g`)
- [ ] Dokka API docs
- [x] Integration tests (generate → compile with real kotlinc → instantiate →
      JSON document round-trip via Jackson; XML document round-trips arrive
      with the annotation modes)
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
3. ~~**Kotlin package naming**~~ **Resolved**: `NamespacePackageMapper` in core derives packages from namespace URIs (JAXB-style reverse-domain for http(s), ordered segments for urn), with `basePackage`/`overrides`/`defaultPackage` config and deterministic collision suffixes. See `docs/namespace-mapping.md`.
4. **Annotation conflicts**: A field might need both a kotlinx and a Jackson annotation; should we emit both or let the user pick a mode? Current lean: annotation mode is a single codegen option (`schema2class-9oo`, `schema2class-n0g`).
