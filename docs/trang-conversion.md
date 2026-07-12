# DTD and RELAX NG via trang

schema2class does not implement DTD or RELAX NG parsers. Use `trang` to convert
those schema formats to XSD, then run schema2class on the generated XSD.

## Decision

Direct DTD and RELAX NG parser support is intentionally out of scope. Trang
already implements these schema frontends and can emit XSD, which is the XML
schema format schema2class owns directly. Keeping conversion outside the core
parser avoids duplicating schema-language semantics that are only needed for
legacy DTDs and publishing-oriented RELAX NG contracts.

schema2class may add an optional wrapper later if users need one, but the default
CLI and Gradle plugin should not bundle Trang or pull it into every install.

## Install trang

Use the `jing-trang` distribution from <https://github.com/relaxng/jing-trang>.
The upstream tool is stable, BSD-licensed, and published as `org.relaxng:trang`
for JVM builds that want to manage it through Maven/Gradle.

Common package-manager names vary by platform, so verify the installed command:

```bash
trang -h
```

## Convert DTD to XSD

Example using the local XBEL sample:

```bash
mkdir -p build/schema2class-converted
trang samples/xbel-1.0.dtd build/schema2class-converted/xbel-1.0.xsd
schema2class generate \
  --input build/schema2class-converted/xbel-1.0.xsd=org.example.xbel \
  --output build/generated/schema2class/kotlin
```

If the DTD references external entities, run `trang` from a directory where those
relative paths resolve, or pass absolute paths.

## Convert RELAX NG to XSD

RELAX NG XML syntax:

```bash
trang schema.rng build/schema2class-converted/schema.xsd
schema2class generate \
  --input build/schema2class-converted/schema.xsd=com.example.generated \
  --output build/generated/schema2class/kotlin
```

RELAX NG compact syntax:

```bash
trang schema.rnc build/schema2class-converted/schema.xsd
schema2class generate \
  --input build/schema2class-converted/schema.xsd=com.example.generated \
  --output build/generated/schema2class/kotlin
```

## Scope

This conversion path generates Kotlin payload classes from the structural schema.
DTD entity declarations, RELAX NG annotations, and co-constraint rules that do not
map into XSD structural types are not represented in schema2class IR.

If conversion produces multiple XSD files, pass the entry XSD to schema2class. The
XSD parser resolves local `xs:include` and `xs:import` references.
