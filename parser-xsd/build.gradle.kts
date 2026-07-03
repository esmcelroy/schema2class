dependencies {
    implementation(project(":core"))
    implementation(libs.kotlin.stdlib)
    // XML parsing via JDK built-in StAX (javax.xml.stream) — no extra dependency needed

    testImplementation(project(":codegen-kotlin"))
    testImplementation(libs.junit.api)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.engine)
}
