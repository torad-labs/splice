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
        // Unused-return-value checker (experimental, Kotlin 2.2+): a discarded return of a MARKED
        // function — most of the stdlib, and whatever carries @MustUseReturnValues — is reported, the
        // compiler-level half of the swallow-into-null discipline (the ast-grep walls are the
        // write-time half). `check` reports nothing for an unmarked function; `full` would mark all.
        freeCompilerArgs.add("-Xreturn-value-checker=check")
        // v0.4.0 review round 2: promoted to an ERROR, as planned "once the codebase is clean" — it was
        // four test lines. As a warning it enforced nothing: SecureFile.ownerOnlyDirectory's reason was
        // dropped by two callers and the build said so only in a log nobody reads.
        freeCompilerArgs.add("-Xwarning-level=RETURN_VALUE_NOT_USED:error")
    }
}

detekt {
    config.setFrom(rootProject.layout.projectDirectory.file("quality/detekt/detekt.yml"))
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
    // HERMETIC HOME. splice resolves its key store and credential files from SPLICE_CONFIG, then
    // XDG_CONFIG_HOME, then $HOME/.config (KeyStorePath.defaultPath, whose own comment already
    // promises "test rigs stay hermetic"). Nothing pointed those at the rig, so any test asserting
    // a credential is ABSENT read the OPERATOR's real ~/.config/splice/keys.toml: StatusCommandTest
    // and MultiProviderDaemonTest's DR-81 both failed on this machine against a genuine stored
    // OPENROUTER_API_KEY while passing on every machine without one. A suite that measures the
    // developer's home instead of its own fixtures is worse than red -- it is red somewhere else.
    val testHome = layout.buildDirectory.dir("test-home").get().asFile
    doFirst { testHome.resolve("config").mkdirs() }
    environment("XDG_CONFIG_HOME", testHome.resolve("config").absolutePath)
    // HERMETIC ENVIRONMENT — the other half of HERMETIC HOME, and the half that was still open.
    // ApiKeyAuthProvider.readKey reads the ENV VAR FIRST, then the key file, then the store
    // (splice/provider/openai/ApiKeyAuthProvider.kt:74-80), so pointing the store at the rig leaves
    // the FIRST source of the operator's real credentials inherited straight from the shell that
    // ran gradle. Measured 2026-09-21 on the consolidated tip with OPENROUTER_API_KEY exported
    // here: MultiProviderDaemonTest's DR-81 arm read the live key where its own fixture had just
    // deleted the key file (so the advertiser never re-armed), and the openrouter turn arm sent
    // that real key to the in-process mock upstream instead of the fixture's. Both were green in
    // CI, where nothing is exported — a suite that measures the developer's shell is red somewhere
    // else, and it walks a genuine credential into a test's own recording. A test that needs a key
    // env var injects its own EnvReader; none may inherit one.
    setEnvironment(environment.filterKeys { !it.endsWith("_API_KEY") && !it.endsWith("_API_TOKEN") })
    // NO REAL BROWSER. SystemBrowserOpener refuses while this is set, so a test that reaches a real
    // OAuth sign-in fails by name instead of opening a login page on the operator's desktop and
    // blocking on a loopback callback that will never arrive. See LoginIo.kt's wall.
    systemProperty("splice.noSystemBrowser", "1")
    systemProperty("user.home", testHome.absolutePath)
    // A CI failure must carry its assertion MESSAGE, not only "AssertionFailedError at X.kt:274".
    // Gradle's default prints the location alone, so the two CI-only failures of the perf
    // telemetry integration arm (runs 33608202738 and 33928312116) left no way to read what was
    // logged instead of the expected line. Failed events only: a green run stays quiet.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}
