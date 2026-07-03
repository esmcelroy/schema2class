dependencies {
    api(project(":core"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinpoet)

    testImplementation(libs.junit.api)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.engine)
}
