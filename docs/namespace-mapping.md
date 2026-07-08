# Namespace → Kotlin Package Mapping

Schema namespaces are URIs — an XSD `targetNamespace` or a JSON Schema `$id`. When you
don't supply an explicit package name, `NamespacePackageMapper`
(`ca.esmcelroy.schema2class.core.naming`) derives one. The convention follows JAXB,
adapted for Kotlin.

## Default derivation

| Namespace | Package |
|---|---|
| `http://maven.apache.org/POM/4.0.0` | `org.apache.maven.pom._4_0_0` |
| `https://www.example.com:8443/schemas/order` | `com.example.schemas.order` |
| `http://example.com/schemas/common.xsd` | `com.example.schemas.common` |
| `urn:un:unece:uncefact:codelist:standard:UNECE:ActionCode:D24A` | `un.unece.uncefact.codelist.standard.unece.actioncode.d24a` |
| _(no targetNamespace)_ | `generated` |

Rules:

1. **`http(s)://` URIs** — host labels reversed (a leading `www.` and any port are
   dropped), then path segments appended in order.
2. **`urn:` URIs** — colon-separated segments in order; no reversal.
3. **Segment sanitization** — lowercase; characters outside `[a-z0-9]` become `_`;
   segments starting with a digit get a `_` prefix; Kotlin hard keywords (`fun`,
   `object`, …) get a `_` suffix; a trailing `.xsd`/`.json`/`.wsdl` extension on the
   last segment is stripped.

## Configuration

```kotlin
val mapper = NamespacePackageMapper(
    // Prepended to every derived package (not to overrides).
    basePackage = "com.corp.generated",
    // Exact-match URI → package; used verbatim. Wins over derivation.
    overrides = mapOf(
        "urn:corp:billing:v2" to "com.corp.contracts.billing",
    ),
    // Used when the schema has no namespace (default: "generated").
    defaultPackage = "com.corp.generated.misc",
)

val model = XsdParser().parse(File("billing.xsd"), mapper)
```

## Collisions

Two distinct namespaces can derive the same package (`urn:test:a-b` and `urn:test:a_b`
both sanitize to `test.a_b`). For multi-schema runs, use `toPackages(namespaces)`,
which guarantees uniqueness by suffixing later entries in iteration order:
`test.a_b`, `test.a_b_2`, `test.a_b_3`, … Iteration order is the caller's input order,
so results are deterministic for a given schema set.

The multi-file XSD import resolver (`schema2class-y7w`) and the Gradle plugin use
`toPackages` so every imported namespace lands in a stable, distinct package.
