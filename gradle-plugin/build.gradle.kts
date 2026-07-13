import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.plugin.publish)
}

gradlePlugin {
    website.set("https://github.com/esmcelroy/schema2class")
    vcsUrl.set("https://github.com/esmcelroy/schema2class")
    plugins {
        create("schema2class") {
            id = "ca.esmcelroy.schema2class"
            implementationClass = "ca.esmcelroy.schema2class.gradle.Schema2ClassPlugin"
            displayName = "schema2class"
            description = "Generate idiomatic Kotlin classes from XSD and JSON Schema"
            tags.set(listOf("kotlin", "xsd", "json-schema", "codegen", "schema"))
            compatibility {
                features {
                    configurationCache = false
                }
            }
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
