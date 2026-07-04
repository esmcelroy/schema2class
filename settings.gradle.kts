plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "schema2class"

include(
    "core",
    "parser-jsonschema",
    "parser-xsd",
    "codegen-kotlin",
    "cli",
    "gradle-plugin",
    "integration-tests",
)
