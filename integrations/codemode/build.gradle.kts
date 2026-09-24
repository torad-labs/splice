import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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

// The compiled runtime suite, for :app's codeModePackagedTest to rerun with the shipped fat jar as the
// worker classpath: a language, global or service registration that shading adds or loses shows only
// there. Before LAYOUT-01 the suite lived in :app and that task ran it directly.
val packagedRuntimeTests: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(packagedRuntimeTests.name, tasks.named<KotlinCompile>("compileTestKotlin").flatMap { it.destinationDirectory })
}
