# Contributing

schema2class is organized around a shared IR. Parser work should add or refine
IR data; codegen work should consume IR without depending on source-format
details unless the IR explicitly carries them.

## Local setup

Use the Gradle wrapper with JDK 21:

```bash
./gradlew test detekt jacocoRootReport
```

In this repository, local agent workflows source SDKMAN first:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && ./gradlew test
```

## Test expectations

- Parser behavior gets parser unit tests and round-trip fixture coverage.
- Codegen behavior gets source-level codegen tests.
- Runtime serialization behavior extends the integration tests that generate,
  compile, load, and round-trip generated classes.
- Gradle and CLI options get functional or command tests.

## Issue tracking

Project work is tracked in Beads. Use `bd ready` to find available work,
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
