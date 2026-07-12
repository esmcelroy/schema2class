# Annotation Modes

`annotationMode` controls which serialization annotations are emitted. The same
mode applies to all generated types for a schema spec.

| Mode | Use when | Main annotations |
|---|---|---|
| `NONE` | You want plain Kotlin models. | None. |
| `KOTLINX_SERIALIZATION` | JSON models should use kotlinx.serialization. | `@Serializable`, `@SerialName`, contextual serializers. |
| `XMLUTIL` | XSD models should round-trip XML with xmlutil. | kotlinx annotations plus `@XmlSerialName`, `@XmlElement`, `@XmlValue`. |
| `JACKSON` | Models should serialize with Jackson JSON or Jackson XML. | `@JsonProperty`, `@JsonInclude`, Jackson XML annotations. |

## Null omission

Set `omitNulls = true` in Jackson mode to emit:

```kotlin
@JsonInclude(JsonInclude.Include.NON_NULL)
```

For kotlinx.serialization, keep nullable properties defaulted to `null` and
configure the runtime encoder:

```kotlin
val json = Json { encodeDefaults = false }
```

## Numeric enum fallback

Set `enumUnknownFallback = true` in Jackson mode for integer-valued enums. The
generated enum stores a numeric `wireValue`, annotates it with `@JsonValue`, and
adds a `@JsonCreator` factory that returns `UNKNOWN` for unrecognized numeric
values.

Kotlinx numeric unknown fallback is not generated yet. String enum wire names
still use `@SerialName` in kotlinx mode.

## XML content and attributes

For XSD simple content, xmlutil mode emits `@XmlValue` on the generated content
property and marks attributes with `@XmlElement(false)`. Jackson mode emits the
Jackson XML equivalents for XML-sourced models.

## Contextual types

`BigDecimal`, date/time, and duration types need contextual serializers in
kotlinx and xmlutil modes. Register serializers in the runtime format as needed
for your application.
