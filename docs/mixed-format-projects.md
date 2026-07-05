# Mixed-Format Projects: XML Envelope with an Embedded JSON Payload

A common integration shape: an XML document (XSD-described) where one element's
text content is a JSON document (JSON Schema-described). schema2class handles
both halves in one Gradle invocation — no IR-level linkage needed, and none is
performed: the JSON-bearing element stays a `String` in the envelope class, and
the payload class is generated from its own schema.

## Build configuration

```kotlin
plugins {
    kotlin("jvm")
    id("io.github.schema2class")
}

schema2class {
    schemas {
        create("envelope") {
            source = file("schemas/envelope.xsd")
            annotationMode = "XMLUTIL"          // XML round-trip via pdvrieze/xmlutil
            // packageName omitted → derived from targetNamespace
            // xs:import / xs:include resolved transitively
        }
        create("payload") {
            source = file("schemas/payload.schema.json")
            packageName = "com.corp.payload"     // required for JSON Schema
            annotationMode = "KOTLINX_SERIALIZATION"
        }
    }
}
```

`schema2classGenerate` runs before `compileKotlin` automatically (the generated
directory is wired into the main source set when the Kotlin JVM plugin is present).

## Using the generated classes together

Suppose the envelope XSD declares `<xs:element name="payload" type="xs:string"/>`
and the JSON Schema describes `TelemetryPayload`:

```kotlin
val payload = TelemetryPayload(deviceId = "dev-42", recordedAt = now, readings = readings)

// Embed: serialize the payload to JSON text, place it in the envelope field
val envelope = Envelope(
    messageId = "m-1",
    payload = Json.encodeToString(payload),
)

// Send: the envelope round-trips as XML
val xmlText = XML.encodeToString(envelope)

// Receive: decode the envelope, then decode the embedded JSON
val received = XML.decodeFromString<Envelope>(xmlText)
val receivedPayload = Json.decodeFromString<TelemetryPayload>(received.payload)
```

## Per-schema configuration reference

| Property | XSD | JSON Schema |
|---|---|---|
| `source` | required | required |
| `packageName` | optional — omitted: derive from namespace(s) + resolve imports; set: single-document parse into that package | **required** |
| `packageOverrides` | namespace URI → package map for multi-file resolution | ignored |
| `annotationMode` | `NONE` / `KOTLINX_SERIALIZATION` / `XMLUTIL` | same (XMLUTIL adds no value for JSON) |
