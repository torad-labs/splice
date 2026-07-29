// NEW: shared Kotlin/JVM configuration for every gateway module (P1-GRADLE).
// Kind-specific rules (dependency law, explicitApi) live in splice.module-law.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Unused-return-value checker (experimental, Kotlin 2.2+): a discarded non-Unit return is a
        // warning — the compiler-level half of the swallow-into-null discipline (the ast-grep wall
        // in checks/ is the write-time half). Promote to error once the codebase is clean.
        freeCompilerArgs.add("-Xreturn-value-checker=check")
    }
}

detekt {
    config.setFrom(rootProject.layout.projectDirectory.file("detekt.yml"))
    buildUponDefaultConfig = true
}

// VERSIONS COME FROM THE CATALOG, never a literal (2026-07-29). detekt-formatting and junit-bom were
// pinned here as bare strings while `detekt` and `junit` already lived in libs.versions.toml, so a
// catalog bump left BOTH behind silently: detekt-formatting at a version the detekt plugin no longer
// matches, and junit-bom disagreeing with the platform the modules resolve. Nothing fails loudly —
// you get a skew, which is the failure mode a version catalog exists to make impossible.
//
// A precompiled script plugin gets no generated `libs` accessor, which is why the literals were here
// in the first place; VersionCatalogsExtension is the supported way to reach it from this context.
private val catalog = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

private fun catalogVersion(alias: String): String =
    catalog.findVersion(alias).orElseThrow {
        // Fail LOUD: a missing alias must not silently fall back to a literal, or the skew returns
        // wearing the fix's clothes.
        GradleException("version catalog has no `$alias` — libs.versions.toml and this convention plugin disagree")
    }.requiredVersion

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:${catalogVersion("junit")}"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    // the kit detekt.yml carries a `formatting:` section (ktlint rules) — needs this plugin
    "detektPlugins"("io.gitlab.arturbosch.detekt:detekt-formatting:${catalogVersion("detekt")}")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Gradle's 512m worker default intermittently kills the 1000-stream load test mid-gate
    // (worker dies -> bare java.io.EOFException, 2026-07-18 x2). 2g since the upstream client moved
    // from ktor CIO to the JDK HttpClient engine (CIO busy-spun the CPU) — the JDK engine holds more
    // per-connection state, so the 1000-stream CEILING test needs the extra heap. Real load is tens
    // of streams (far under 1g either way); this only funds the stress ceiling.
    maxHeapSize = "2g"
}
