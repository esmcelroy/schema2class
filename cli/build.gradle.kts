plugins {
    application
}

application {
    mainClass.set("ca.esmcelroy.schema2class.cli.Schema2ClassCliKt")
    applicationName = "schema2class"
}

distributions {
    main {
        contents {
            from(rootProject.file("LICENSE"))
        }
    }
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
