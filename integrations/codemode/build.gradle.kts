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
    // The published aggregator declares platform POMs as runtime artifacts. Shadow consumes ZIPs,
    // so use its native JAR dependencies directly. Each isolate library is 140-150 MB, so a local build ships only the
    // host's (the one a local run can load); CI and the release build ship every published platform, and
    // -Psplice.allPlatforms=true asks for them locally.
    val everyPlatform = listOf("linux-amd64", "linux-aarch64", "darwin-aarch64", "windows-amd64")
    val hostOs = providers.systemProperty("os.name").get().lowercase()
    val hostArch = providers.systemProperty("os.arch").get().lowercase()
    val hostPlatform = when {
        hostOs.startsWith("windows") -> "windows-amd64"
        hostOs.startsWith("mac") -> "darwin-aarch64"
        hostArch == "aarch64" || hostArch == "arm64" -> "linux-aarch64"
        else -> "linux-amd64"
    }
    val shipEvery = providers.environmentVariable("CI").isPresent ||
        providers.gradleProperty("splice.allPlatforms").orNull == "true"
    for (platform in if (shipEvery) everyPlatform else listOf(hostPlatform)) {
        runtimeOnly("org.graalvm.js:js-isolate-$platform-community:${libs.versions.graaljs.get()}")
    }
    implementation(libs.graaljs.community)
    // Statement boundaries and binding scopes come from the exact parser used by the worker.
    implementation("org.graalvm.js:js-language:${libs.versions.graaljs.get()}")
    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
}

// The stamp is Java deliberately: it is compiled AFTER the Kotlin bytes it identifies, avoiding a
// self-referential fingerprint. Kotlin sees the checked-in Java bridge's stable method signatures.
val identitySources = layout.buildDirectory.dir("generated/sources/worker-identity")
val workerIdentity = tasks.register<splice.release.WorkerArtifactIdentity>("generateWorkerArtifactIdentity") {
    classes.set(tasks.named<KotlinCompile>("compileKotlin").flatMap { it.destinationDirectory })
    source.set(identitySources.map { it.file("splice/codemode/WorkerArchiveFingerprint.java") })
}
// Do not give this directory a builtBy task: compileKotlin must precede generation, not depend on it.
sourceSets.main { java.srcDir(identitySources) }
tasks.named("compileJava") { dependsOn(workerIdentity) }

// The worker is a child JVM started from a classpath string; the tests hand it this module's own
// runtime classpath. :app's codeModePackagedTest hands the same property the shipped fat jar.
tasks.test {
    jvmArgumentProviders.add(splice.testing.MachineLocalProperties(provider { mapOf("codeMode.testClasspath" to sourceSets.test.get().runtimeClasspath.asPath) }))
}

// The compiled runtime suite, for :app's codeModePackagedTest to rerun with the shipped fat jar as the
// worker classpath: a language, global or service registration that shading adds or loses shows only
// there. Before LAYOUT-01 the suite lived in :app and that task ran it directly.
val packagedRuntimeTests: Configuration = configurations.create("packagedRuntimeTests") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(packagedRuntimeTests.name, tasks.named<KotlinCompile>("compileTestKotlin").flatMap { it.destinationDirectory })
}
