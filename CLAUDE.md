# Project Instructions for AI Agents

schema2class is a Kotlin-native library that generates idiomatic Kotlin classes
from XSD and JSON Schema documents. See `PRD.md` for the product plan (its phase
checklists are kept current) and `docs/` for design notes (domain survey,
namespace mapping, mixed-format projects).

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->

> **Local-only exception to the block above:** this repository currently has NO
> git remote (`bd prime` confirms: "No git remote configured. Issues are saved
> locally only."). The pull/push steps do not apply until a remote exists — the
> session-completion bar is: all changes **committed**, all finished beads
> **closed**, full test suite **green**.

## Build & Test

Java comes from SDKMAN (Temurin 21.0.7) and is NOT on the default shell PATH.
**Every** Gradle/Java command must source SDKMAN first:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && ./gradlew test          # full suite
source "$HOME/.sdkman/bin/sdkman-init.sh" && ./gradlew :core:test    # one module
```

- Gradle 8.14.1 (wrapper), Kotlin 1.9.25, JVM toolchain 21, bytecode target 17.
- A Gradle deprecation warning about `isConfigurationCacheRequested` comes from
  the Kotlin 1.9 plugin itself — not fixable here, ignore it.
- Per-suite results: `*/build/test-results/test/*.xml` (grep `<testsuite` for
  counts, `<failure message` for details — often faster than rerunning).

## Architecture Overview

The IR is the central contract: parsers produce `SchemaModel`, codegen consumes it.

```
core/                 IR (SchemaModel, TypeDefinition, TypeRef, PropertyDefinition,
                      PropertyKind, Constraint) + NamespacePackageMapper
                      + InheritanceFlattener (superType chains → flattened properties)
parser-xsd/           XSD → IR. JDK DOM, no external deps. parseWithImports() resolves
                      xs:include/xs:import (one SchemaModel per namespace)
parser-jsonschema/    JSON Schema draft-07 → IR. Jackson. parseWithRefs() resolves
                      external $refs (one SchemaModel per document)
codegen-kotlin/       IR → Kotlin source via KotlinPoet. AnnotationMode:
                      NONE | KOTLINX_SERIALIZATION | XMLUTIL (superset, adds
                      @XmlSerialName/@XmlElement/@XmlValue from PropertyKind)
                      | JACKSON
cli/                  Clikt: schema2class generate -i FILE[=PACKAGE]... (mixed formats)
gradle-plugin/        id "ca.esmcelroy.schema2class", schema2classGenerate task,
                      schema2class { schemas { ... } } DSL, source-set wiring
integration-tests/    Test-only module: generates, compiles in-test
                      (kotlin-compile-testing + serialization plugin), round-trips
                      real JSON/XML documents
```

Key design decisions (rationale in git history and `docs/`):
- **Nullability lives on `PropertyDefinition.nullable`**, never on `TypeRef`.
- **Schema inheritance is flattened** (`InheritanceFlattener`) because Kotlin data
  classes are final; `superType` is kept as provenance only — codegen must not
  emit `: Parent()`.
- **Wire names vs Kotlin names**: `schemaName` keeps the exact wire form (for
  `@SerialName` etc.); `kotlinName` follows Kotlin conventions (`currencyID` →
  `currencyId`, enum constants SCREAMING_SNAKE, UNECE numeric codes get names
  from `ccts:Name` annotations).
- **Degrade, don't throw**: unresolvable refs/imports/groups warn to stderr and
  produce a stable named reference so partial generation stays useful.

## Conventions & Patterns

- **Tests**: JUnit 5 + kotest matchers, backtick sentence names. Every feature
  lands with parser/codegen unit tests; parser features also get round-trip
  coverage (`XsdRoundTripTest` / `JsonSchemaRoundTripTest`); anything touching
  generated-code runtime behavior extends `GenerateCompileRoundTripTest`.
- **Fixtures**: vendored (committed) under `*/src/test/resources/` — real-world
  schemas keep their license headers (maven-4.0.0.xsd, spring-beans.xsd).
  `samples/` is gitignored scratch space; never reference it from tests.
- **Found a bug while doing something else?** File a bead with a repro sketch
  (see schema2class-y0z, schema2class-1mz) instead of drive-by fixing.
- **Commits**: imperative subject with module scope (`feat(parser-xsd): ...`),
  body says `Resolves schema2class-<id>` and ends with the current test count.
  Commit `.beads/issues.jsonl` alongside the work it tracks.
- **When closing a bead that changes scope/status**, update `PRD.md`'s phase
  checklists in the same commit.
- **KotlinPoet gotchas**: `value` is backtick-escaped as a soft keyword (don't
  assert on the unescaped form); enum inline comments are added by string
  post-processing (`applyEnumComments`); data classes need ≥1 constructor
  property — property-less ComplexTypes emit plain classes.
- **kotlinx quirk**: `BigDecimal`/`java.time` types get `@Contextual` —
  consumers must register serializers; xmlutil does not honor `@XmlValue` on
  `@Contextual` content properties (open bug schema2class-1mz).

## Current State (July 2026)

Phases 1–4 of the PRD are complete; all P0/P1/P2 beads closed. Both parsers,
codegen with three annotation modes, CLI, and Gradle plugin work end-to-end,
including mixed-format runs (.xsd + schema.json in one invocation — the
project owner's primary use case: XML envelope with an embedded JSON payload).
Remaining work is P3/P4 polish: `bd ready` for the list.
