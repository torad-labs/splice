plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.serialization.json)
    // Shared heap availability is a framework-free StateFlow across owner modules.
    api(libs.kotlinx.coroutines.core)
    // test-only: drive the suspend perf helpers; production :core stays framework-free
    testImplementation(libs.kotlinx.coroutines.test)
    // LawReadGuard is a JUnit extension; the launcher runs a fixture law in LawReadGuardTest.
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
}

// Fixture laws that must FAIL when the guard works. They live in their own source set: it is on the launcher test's classpath
// and in no Test task's testClassesDirs, so the build never runs one as a test and no skip condition is needed.
val lawFixtures = sourceSets.create("lawFixtures") {
    compileClasspath += sourceSets.testFixtures.get().output + sourceSets.testFixtures.get().compileClasspath
    runtimeClasspath += output + compileClasspath
}

dependencies {
    "testImplementation"(lawFixtures.output)
    "testRuntimeOnly"(lawFixtures.runtimeClasspath)
}
