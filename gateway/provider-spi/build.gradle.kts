plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
    `java-test-fixtures`
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.zstd.jni)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

// V4-117: the AGENTS.md retry-matrix block is GENERATED from RetryMatrix, and RetryMatrixTableTest
// polices it. Gradle caches a test task on its declared inputs, and AGENTS.md was not one — so an
// edit to the committed table left the leg UP-TO-DATE and green. That was MEASURED rather than
// assumed: mutating only AGENTS.md passed a plain run and failed only under --rerun-tasks, which
// proved the leg sound and its TRIGGER missing. Declaring the file here makes any change to it
// invalidate the task, so the leg fires on exactly the drift it exists to catch.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("../AGENTS.md")).withPropertyName("retryMatrixTable")
}
