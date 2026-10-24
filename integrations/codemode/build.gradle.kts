import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import splice.release.IsolatePlatforms

plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
    `java-test-fixtures`
}

val nativeHostResources = tasks.register<splice.release.NativeCodeModeHost>("stageNativeCodeModeHost") {
    artifacts.set(layout.projectDirectory.dir("native"))
    platforms.set(
        IsolatePlatforms.shipped(
            allPlatforms = providers.gradleProperty("splice.allPlatforms").orNull?.toBooleanStrictOrNull(),
            hosted = providers.environmentVariable("CI").isPresent,
            os = providers.systemProperty("os.name").get(),
            arch = providers.systemProperty("os.arch").get(),
        ),
    )
    destination.set(layout.buildDirectory.dir("generated/resources/native-code-mode-host"))
}
sourceSets.main { resources.srcDir(nativeHostResources.flatMap { it.destination }) }

dependencies {
    implementation(project(":core"))
    // JvmCodeModeRuntime implements the upstream-owned CodeModeRuntime port, so the port is API.
    api(project(":integrations-upstream"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.graaljs.polyglot)
    // The published aggregator declares platform POMs as runtime artifacts. Shadow consumes ZIPs,
    // so use its native JAR dependencies directly.
    //
    // WHICH PLATFORMS A BUILD SHIPS IS A CHOICE THE BUILD IS TOLD, in the property splice.allPlatforms. Each isolate
    // library is 140-150 MB, so a developer's own build leaves it unset and ships only the host's, the one a local run
    // can load. A RELEASE SHIPS ALL FOUR: -Psplice.allPlatforms=true, which tools/release gives every build it makes
    // (release beta, release verify) and which a hand-made release build must give too. With the property unset, a
    // hosted run (CI set) ships all four; -Psplice.allPlatforms=false forces the host's alone even there.
    // :app:stageRelease refuses a jar that lacks any of them, so a release cannot go out with fewer by forgetting this.
    val shipped = IsolatePlatforms.shipped(
        allPlatforms = providers.gradleProperty("splice.allPlatforms").orNull?.toBooleanStrictOrNull(),
        hosted = providers.environmentVariable("CI").isPresent,
        os = providers.systemProperty("os.name").get(),
        arch = providers.systemProperty("os.arch").get(),
    )
    for (platform in shipped) {
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
    jvmArgumentProviders.add(
        splice.testing.MachineLocalProperties(
            provider {
                mapOf("codeMode.testClasspath" to sourceSets.test.get().runtimeClasspath.asPath)
            },
        ),
    )
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
