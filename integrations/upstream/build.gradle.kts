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
    inputs.file(rootProject.file("AGENTS.md")).withPropertyName("retryMatrixTable")
}

// V4-307: the classes that take a refused thread start that stops something for the whole JVM: OkHttp's
// task runner made to refuse the threads it starts (TaskRunner.startAnotherThread counts a start before
// making it, so it never starts another), and okio's timeouts left off (a refused watchdog start leaves its
// sentinel set). Each is fair only while that thread has never started in its JVM, so each class runs in a
// JVM of its own and `test` never runs them. check carries the task, so the gate does.
val threadRefusalClasses = listOf(
    "splice.upstream.transport.RefusedThreadStartPostTest",
    "splice.upstream.transport.AfterRefusedThreadStartTest",
    "splice.upstream.transport.OkioTimeoutsRefusedTest",
)
tasks.named<Test>("test") {
    filter { threadRefusalClasses.forEach { excludeTestsMatching(it) } }
}
val threadRefusalTest = tasks.register<Test>("threadRefusalTest") {
    description = "V4-307: the posts that take a refused thread start, one JVM per class."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    forkEvery = 1L
    filter { threadRefusalClasses.forEach { includeTestsMatching(it) } }
}
tasks.named("check") { dependsOn(threadRefusalTest) }
