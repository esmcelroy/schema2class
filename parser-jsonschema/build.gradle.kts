dependencies {
    api(project(":core"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.kotlin)

    testImplementation(libs.junit.api)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.engine)
}
