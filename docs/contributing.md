# Contributing

schema2class is organized around a shared IR. Parser work should add or refine
IR data; codegen work should consume IR without depending on source-format
details unless the IR explicitly carries them.

## Local setup

Use the Gradle wrapper with JDK 21:

```bash
./gradlew test detekt jacocoRootReport
```

## Test expectations

- Parser behavior gets parser unit tests and round-trip fixture coverage.
- Codegen behavior gets source-level codegen tests.
- Runtime serialization behavior extends the integration tests that generate,
  compile, load, and round-trip generated classes.
- Gradle and CLI options get functional or command tests.

## Issue tracking

Maintainers track implementation work in Beads. Public bug reports and feature
requests should start as GitHub issues; maintainers may mirror accepted work
into Beads before implementation.

When working inside this repository, use `bd ready` to find available work,
`bd show <id>` to inspect it, and close beads only after tests and docs are in
sync.

## Documentation

Docs are built with MkDocs Material:

```bash
pip install -r requirements-docs.txt
mkdocs serve
mkdocs build --strict
```

Keep user-facing guides under `docs/`. The GitHub Pages workflow builds the site
strictly on pull requests and deploys from `main`.

## Pull requests

Pull requests should include:

- a focused description of the behavior change
- tests for parser, codegen, CLI, or Gradle behavior that changed
- documentation updates for user-visible behavior or configuration
- generated-code drift verification when generation output changes

Keep unrelated formatting, dependency, and refactoring changes out of feature
pull requests unless they are necessary for the change.

## Releases

Release publishing is maintainer-only. See [Release Process](release.md) for the
current dry-run and publishing workflow.
