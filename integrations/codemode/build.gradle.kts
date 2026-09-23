plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core"))
    // JvmCodeModeRuntime implements the upstream-owned CodeModeRuntime port, so the port is API.
    api(project(":integrations-upstream"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.graaljs.polyglot)
    implementation(libs.graaljs.community)
    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
}

// The worker is a child JVM started from a classpath string; the tests hand it this module's own
// runtime classpath. :app's codeModePackagedTest hands the same property the shipped fat jar.
tasks.test {
    systemProperty("codeMode.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}
