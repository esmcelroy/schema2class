# Compatibility Matrix

This matrix describes the supported schema surface for the current codebase.
Unsupported features should degrade to stable generated references where
possible instead of aborting generation.

## XSD

| Feature | Status | Notes |
|---|---|---|
| `xs:complexType` | Supported | Generates Kotlin classes or data classes. |
| `xs:sequence` | Supported | Preserves element order in constructor properties. |
| `xs:attribute` | Supported | Required attributes are non-null; optional attributes default to null. |
| `xs:simpleContent` | Supported | Text content is emitted as a `value` property. |
| `xs:simpleType` restrictions | Supported | Aliases or value classes; constraints are retained in IR. |
| Enumerations | Supported | String and integer enum values, including numeric Jackson wire values. |
| `default` / `fixed` | Supported | Emits Kotlin defaults; fixed values can emit guards. |
| `minOccurs` / `maxOccurs` | Supported | Maps to nullability and `List<T>`. |
| `xs:choice` | Partial | Whole-content choices become sealed unions; some nested choices flatten to nullable properties. |
| `xs:group` / `xs:attributeGroup` | Supported | Resolved into the containing type. |
| Element refs | Supported | Local and imported references resolve through the parser graph. |
| `xs:extension` / `xs:restriction` | Supported | Inheritance chains are flattened for Kotlin data-class output. |
| `xs:include` / `xs:import` | Supported | `parseWithImports` produces one model per namespace. |
| WSDL `<types>` | Supported | Extracts embedded XSD; service stubs are out of scope. |
| DTD / RELAX NG | Conversion path | Convert with trang first; see [DTD and RELAX NG Conversion](trang-conversion.md). |

## JSON Schema

| Feature | Status | Notes |
|---|---|---|
| Object schemas | Supported | Generates Kotlin data classes. |
| Properties / `required` | Supported | Required controls nullability; optional properties default to null. |
| Primitive types | Supported | String, integer, number, boolean, object, and array shapes map to Kotlin types. |
| Arrays | Supported | Homogeneous arrays map to `List<T>`. |
| Enums | Supported | String enums and integer-valued enums are supported. |
| `default` / `const` | Supported | Emits Kotlin defaults where representable. |
| `oneOf` / `anyOf` | Supported | Generates sealed class hierarchies. |
| `$ref` / `$defs` | Supported | Same-document, external, shared, and circular references are covered. |
| `title` / `description` | Supported | Titles feed naming conventions; descriptions become KDoc. |
| `x-object-name` / `x-object-type` | Supported | JSON Schema naming extensions. |
| External name bindings | Supported | Consumer-local sidecar for friendly Kotlin names. |
| Range, length, pattern, item constraints | Supported | Retained in IR; optional generated `require` guards. |
| Hyper-Schema vocabularies | Out of scope | Not planned for v1. |

## Kotlin output

| Output shape | Status | Notes |
|---|---|---|
| Data classes | Supported | Used when a complex type has constructor properties. |
| Plain classes | Supported | Used for property-less complex types. |
| Enums | Supported | Wire values and Kotlin constant names are separate. |
| Sealed classes | Supported | Used for unions. |
| Type aliases | Supported | Default for simple constrained scalar types. |
| Value classes | Supported | Opt-in with `valueClasses` / `--value-classes`. |
| KDoc | Supported | Carries schema documentation onto generated declarations. |
