plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        create("schema2class") {
            id = "io.github.schema2class"
            implementationClass = "io.github.schema2class.gradle.Schema2ClassPlugin"
            displayName = "schema2class"
            description = "Generate idiomatic Kotlin classes from XSD and JSON Schema"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":parser-jsonschema"))
    implementation(project(":parser-xsd"))
    implementation(project(":codegen-kotlin"))
    implementation(libs.kotlin.stdlib)

    testImplementation(gradleTestKit())
    testImplementation(libs.junit.api)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.engine)
}
