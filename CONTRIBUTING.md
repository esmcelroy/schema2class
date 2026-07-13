# Contributing

Thanks for working on schema2class. Start with the detailed contributor guide:

- [docs/contributing.md](docs/contributing.md)

Quick requirements:

- JDK 21
- Gradle wrapper from this repository
- Python tooling only when building the documentation site

Before opening a pull request, run:

```bash
./gradlew test detekt jacocoRootReport
mkdocs build --strict
```

Use GitHub issues for public discussion and bug reports. Maintainers use Beads
for local planning, so a GitHub issue may be mirrored into an internal bead
before implementation.
