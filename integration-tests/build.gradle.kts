// End-to-end tests: parse real schemas → generate Kotlin → compile it in-test →
// instantiate the classes → round-trip documents. No production sources.
dependencies {
    testImplementation(project(":parser-xsd"))
    testImplementation(project(":parser-jsonschema"))
    testImplementation(project(":codegen-kotlin"))

    testImplementation(libs.kotlin.compile.testing)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.kotlin)

    testImplementation(libs.junit.api)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.engine)
}
