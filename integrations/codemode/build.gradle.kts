import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.security.MessageDigest

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
    // so use its four native JAR dependencies directly, preserving every published platform.
    for (platform in listOf("linux-amd64", "linux-aarch64", "darwin-aarch64", "windows-amd64")) {
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
val kotlinClasses = tasks.named<KotlinCompile>("compileKotlin").flatMap { it.destinationDirectory }
val workerIdentity = tasks.register("generateWorkerArtifactIdentity") {
    dependsOn(tasks.named("compileKotlin"))
    inputs.dir(kotlinClasses)
    val output = identitySources.map { it.file("splice/codemode/WorkerArchiveFingerprint.java") }
    outputs.file(output)
    doLast {
        val directory = kotlinClasses.get().asFile
        val entries = directory.walkTopDown().filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(directory).invariantSeparatorsPath }.sorted().toList()
        check(entries.isNotEmpty()) { "Compiled worker identity cannot be empty" }
        val digest = MessageDigest.getInstance("SHA-256")
        for (entry in entries) {
            digest.update(entry.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(directory.resolve(entry).readBytes())
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        val target = output.get().asFile
        target.parentFile.mkdirs()
        target.writeText(
            "package splice.codemode;\nfinal class WorkerArchiveFingerprint {\n" +
                "    static final String VALUE = \"$fingerprint\";\n" +
                "    static final String[] ENTRIES = {" +
                entries.joinToString(",") { "\"$it\"" } + "};\n}\n",
        )
    }
}
// Do not give this directory a builtBy task: compileKotlin must precede generation, not depend on it.
sourceSets.main { java.srcDir(identitySources) }
tasks.named("compileJava") { dependsOn(workerIdentity) }

// The worker is a child JVM started from a classpath string; the tests hand it this module's own
// runtime classpath. :app's codeModePackagedTest hands the same property the shipped fat jar.
tasks.test {
    systemProperty("codeMode.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
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
