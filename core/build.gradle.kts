dependencies {
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.junit.api)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.engine)
}
