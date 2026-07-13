# Release Process

schema2class publishes library artifacts to Maven Central and the Gradle plugin
to the Gradle Plugin Portal. The CLI is published as a Maven artifact and as
application distributions attached to the GitHub release.

## Release Targets

Maven Central artifacts:

| Artifact | Purpose |
|---|---|
| `ca.esmcelroy.schema2class:core` | Shared IR, naming, and package mapping APIs. |
| `ca.esmcelroy.schema2class:parser-xsd` | XSD and WSDL frontend. |
| `ca.esmcelroy.schema2class:parser-jsonschema` | JSON Schema frontend. |
| `ca.esmcelroy.schema2class:codegen-kotlin` | KotlinPoet code generator. |
| `ca.esmcelroy.schema2class:cli` | Command-line application artifact. |
| `ca.esmcelroy.schema2class:gradle-plugin` | Gradle plugin implementation artifact. |

Gradle Plugin Portal:

```kotlin
plugins {
    id("ca.esmcelroy.schema2class") version "0.1.0"
}
```

GitHub release assets:

- `schema2class-<version>.zip`
- `schema2class-<version>.tar`

## Required Secrets

GitHub Actions must have these repository secrets:

| Secret | Source |
|---|---|
| `MAVEN_USERNAME` | Sonatype Central Portal user token username. |
| `MAVEN_PASSWORD` | Sonatype Central Portal user token password. |
| `SIGNING_KEY` | ASCII-armored private GPG key. |
| `SIGNING_PASSWORD` | Private GPG key passphrase. |
| `GRADLE_PUBLISH_KEY` | Gradle Plugin Portal API key. |
| `GRADLE_PUBLISH_SECRET` | Gradle Plugin Portal API secret. |

Export the signing key from a local GPG keychain:

```bash
gpg --list-secret-keys --keyid-format LONG
gpg --armor --export-secret-keys <key-id> > signing-key.asc
```

Copy the full contents of `signing-key.asc` into `SIGNING_KEY`, including the
`BEGIN PGP PRIVATE KEY BLOCK` and `END PGP PRIVATE KEY BLOCK` lines. Keep
`signing-key.asc` out of git.

Publish the public key so Maven Central users can verify signatures:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <key-id>
```

## Dry Run

Run locally:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
./gradlew test detekt jacocoRootReport
./gradlew publishToMavenLocal :cli:distZip :cli:distTar
```

Or run the GitHub `Release` workflow with `dry_run=true`. The dry run verifies
the build, creates local Maven publications, and uploads CLI distributions as a
workflow artifact. It does not publish externally.

## Real Release

1. Make sure `VERSION_NAME` in `gradle.properties` matches the intended version.
2. Confirm CI is green on `main`.
3. Run the `Release` workflow manually with:
   - `version`: the release version, for example `0.1.0`
   - `dry_run`: `false`
4. Watch the workflow. It runs verification, builds CLI distributions, publishes
   to Maven Central, publishes the Gradle plugin, and creates a GitHub release.
5. Maven Central availability can take 10-30 minutes after release.

For the first Gradle Plugin Portal release, expect manual review. The plugin
becomes publicly discoverable after approval.
