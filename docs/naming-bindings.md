# Naming Bindings

schema2class keeps wire names in the IR as `schemaName` and generated Kotlin
identifiers as `kotlinName`. For schemas with abbreviated wire fields, JSON
Schema generation can apply friendlier Kotlin names without changing the source
schema.

## Precedence

For JSON Schema, generated names currently resolve in this order:

1. Programmatic `NamingStrategy`
2. External binding file
3. Schema extensions: `x-object-name` for properties, `x-object-type` for types
4. Sanitized conventions from `title`
5. Wire key fallback

Property and type names are independent. For array properties, schema2class uses
the item title when available and pluralizes it, so an array whose item title is
`IntersectionState` becomes `intersectionStates`.

## Binding File

Use `Type.property = friendlyName` for properties and `SchemaType = FriendlyType`
for types:

```properties
# names.conf
Payload.rg = region
Payload.sl = intersectionStateList
Payload = FriendlyPayload
```

The left side may use either the source schema type name or the generated type
name for property overrides. Binding files are consumer-local sidecars, so shared
schemas do not need Kotlin-specific metadata.

CLI:

```bash
schema2class generate \
  --input payload.schema.json=com.corp.payload \
  --name-bindings names.conf \
  --output build/generated/schema2class/kotlin
```

Gradle:

```kotlin
schema2class {
    schemas {
        create("payload") {
            source = file("schemas/payload.schema.json")
            packageName = "com.corp.payload"
            nameBindings = file("schemas/names.conf")
        }
    }
}
```
