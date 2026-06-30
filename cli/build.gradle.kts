plugins {
    application
}

application {
    mainClass.set("io.github.schema2class.cli.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":parser-jsonschema"))
    implementation(project(":parser-xsd"))
    implementation(project(":codegen-kotlin"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.clikt)

    testImplementation(libs.junit.api)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.engine)
}
