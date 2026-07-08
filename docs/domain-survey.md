# Domain Survey: Market Gap Validation & XML-Adjacent Extension Points

_Survey date: July 2026. Tracked as schema2class-0a7._

## Question 1: Is the gap claim in the PRD accurate?

**Short answer: yes for XSD, partially for JSON Schema.** The PRD's original claim
("no Kotlin-native library for XSD and JSON Schema") needs one correction: an active
Kotlin-native JSON Schema generator exists. No tool covers both formats, and the XSD
side has no living Kotlin-native option at all.

### XSD → Kotlin: gap confirmed

| Tool | Status | Why it doesn't fill the gap |
|---|---|---|
| [KAXB](https://github.com/SixRQ/KAXB) | Abandoned | 15 stars, zero published releases, distribution was via a private TeamCity server. The one prior attempt at exactly our XSD scope — it died. |
| [schema-gen](https://github.com/reaster/schema-gen) | Dormant | Groovy implementation, 30 stars, v0.9.1, README warns it is untested against varied schemas, Gradle plugin explicitly unsupported, mixed content poorly handled. |
| xjc / JAXB | Active but Java-only | The de-facto answer. Generates Java (platform types in Kotlin — nullability erased, no data classes, no sealed hierarchies). The javax→jakarta migration added churn. Will never emit Kotlin. |

Standard practice today, per multiple recent writeups (e.g. [Brightec's WSDL-client
guide](https://www.brightec.co.uk/blog/wsdl-client-generation-with-kotlin-and-gradle)),
is "run xjc/wsimport, get Java, call it from Kotlin." Demand signals:

- A [Kotlin Discussions thread](https://discuss.kotlinlang.org/t/is-there-any-common-approach-to-generate-kotlin-multiplatfom-data-classes-with-validation-annotations-like-jaxb-jsr303-in-java/20956)
  asking for exactly this: JAXB-style generation of Kotlin multiplatform data classes
  with validation annotations. No good answer exists in the thread.
- An [xsd-to-kotlin LLM skill](https://lobehub.com/skills/myrealtrip-air-claudecode-xsd-to-kotlin)
  exists on a skills marketplace — teams are using LLMs to hand-translate XSDs because
  no deterministic tool does it. That is a tooling vacuum, not a solved problem.

### JSON Schema → Kotlin: gap is partial — one real competitor

| Tool | Status | Assessment |
|---|---|---|
| [json-kotlin-schema-codegen](https://github.com/pwall567/json-kotlin-schema-codegen) (pwall567) | **Active** | The real competitor. Draft-07 plus some 2019-09, programmatic `CodeGenerator` API, Kotlin/Java/TypeScript output, `$ref` multi-file resolution, ~105 stars, v0.123 (2025). Single-maintainer. **No XSD support and none planned.** |
| [quicktype](https://quicktype.io/kotlin) | Active | Node/TypeScript CLI, not embeddable in a JVM build. Kotlin output (Klaxon default, kotlinx.serialization option) is a secondary target among ~20 languages. Not library-first, no XSD. |
| jsonschema2pojo | Active | Java output only. [Users ask for schema support in Kotlin-example tools instead](https://github.com/wuseal/JsonToKotlinClass/issues/108). |
| JsonToKotlinClass et al. | Active | IDE plugins that infer classes from JSON *examples*, not schemas. Different problem. |

### Adjacent proof point: Fabrikt

[Fabrikt](https://github.com/cjbooms/fabrikt) (OpenAPI 3 → Kotlin) is the closest
architectural sibling: Kotlin-native, KotlinPoet-based, Jackson + kotlinx.serialization
modes, CLI + Gradle plugin, 276 stars, production-tested at Zalando, latest release
June 2026. It proves (a) Kotlin-native schema codegen built on KotlinPoet gets real
adoption, and (b) the pattern works — but it accepts **only** OpenAPI 3 specs, not raw
JSON Schema and not XSD. OpenAPI 3.1 embeds JSON Schema 2020-12, so our parser is
reusable for a future OpenAPI frontend without competing head-on today.

### Revised positioning

1. **XSD → idiomatic Kotlin is an empty niche.** Every alternative is abandoned,
   Java-only, or not Kotlin. This is schema2class's beachhead.
2. **The unified IR is the moat.** No tool handles both XSD and JSON Schema. Teams
   with mixed contracts (enterprise XML + modern JSON APIs) currently need two
   toolchains with two different output styles.
3. **On JSON Schema alone we are not first.** Differentiators vs pwall567:
   the XSD story, one IR/one Gradle plugin across formats, KotlinPoet codegen, and
   XML-aware annotation modes (below). Worth stating honestly in the PRD.

---

## Question 2: XML-related extension points

Surveyed in rough order of value:

### xmlutil annotation mode — high value, near-term

[pdvrieze/xmlutil](https://github.com/pdvrieze/xmlutil) is the de-facto XML format
for kotlinx.serialization and is Kotlin Multiplatform. Generated classes from XSD are
only actually *usable* for XML round-tripping if they carry xmlutil's annotations
(`@XmlSerialName`, `@XmlElement`, `@XmlValue`) — which map one-to-one onto IR
concepts we already planned: element vs attribute vs simpleContent body is exactly
`PropertyKind` (schema2class-jo4). This closes the loop the Kotlin Discussions thread
asked for: XSD → KMP-ready serializable Kotlin. Filed as a new bead; raises the
practical priority of jo4.

### WSDL (types section) — medium value, clear scope boundary

SOAP is alive in enterprise/government integration, and the current Kotlin answer is
JAXWS `wsimport` → Java. A WSDL document embeds XSD in `<wsdl:types>`; a thin frontend
that extracts those schemas and feeds our existing XSD parser produces typed payload
classes (the hard 80%) while explicitly *not* generating service stubs/bindings
(out of scope — that's an HTTP/SOAP client concern, not codegen). Filed as a bead.

### DTD and RELAX NG — low value, near-zero cost via conversion

- The samples dir already contains `xbel-1.0.dtd`.
- [trang](https://relaxng.org/jclark/trang-manual.html) (James Clark, BSD,
  [jing-trang](https://github.com/relaxng/jing-trang)) converts DTD and RELAX NG
  (both syntaxes) to XSD. It is barely maintained but stable and feature-complete.
- Recommendation: do **not** write DTD/RNG parsers. Document (and optionally wrap in
  the CLI) a trang conversion step: DTD/RNG → XSD → schema2class. RELAX NG demand is
  a publishing niche (DocBook, TEI, ODF); DTD demand is legacy. See
  [trang-conversion.md](trang-conversion.md) for the operational recipe.

### Jackson XML — fold into existing bead

`jackson-dataformat-xml` is the JVM-only alternative to xmlutil. The existing Jackson
annotation-mode bead (schema2class-n0g) should cover `@JacksonXmlProperty` /
`@JacksonXmlElementWrapper` for XSD-sourced models, not just JSON annotations —
description updated.

### Not pursuing

- **Schematron**: rule-based co-constraints, not structural — nothing to generate
  classes from.
- **WADL, XML Beans, Castor**: dead ecosystems.
- **OpenAPI**: already a tracked future frontend (PRD non-goal for v1); the JSON
  Schema parser is the reuse path.

---

## Sources

- https://github.com/pwall567/json-kotlin-schema-codegen
- https://github.com/SixRQ/KAXB
- https://github.com/reaster/schema-gen
- https://quicktype.io/kotlin
- https://github.com/cjbooms/fabrikt
- https://github.com/pdvrieze/xmlutil
- https://discuss.kotlinlang.org/t/is-there-any-common-approach-to-generate-kotlin-multiplatfom-data-classes-with-validation-annotations-like-jaxb-jsr303-in-java/20956
- https://github.com/wuseal/JsonToKotlinClass/issues/108
- https://www.brightec.co.uk/blog/wsdl-client-generation-with-kotlin-and-gradle
- https://github.com/relaxng/jing-trang
- https://relaxng.org/jclark/trang-manual.html
- https://lobehub.com/skills/myrealtrip-air-claudecode-xsd-to-kotlin
