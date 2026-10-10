// NEW: shared Kotlin/JVM configuration for every gateway module (P1-GRADLE).
// Kind-specific rules (dependency law, explicitApi) live in splice.module-law.
import splice.hygiene.CatalogReader

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
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

// v0.4.0 release, held to the 1.0 bar: every source set compiles warning-free and stays that way. CI run
// 35963713936 (66 compile tasks, none from cache) had four warnings in main sources, each fixed with its
// documented annotation; a warning left standing hides the next real one. Test sources followed once
// #211 cleared theirs (the Ktor readUTF8Line deprecation, redundant `!!`, an opt-in, two named
// configurations): a test that compiles on a deprecated API is the next release's broken build.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.allWarningsAsErrors.set(true)
}

detekt {
    config.setFrom(rootProject.layout.projectDirectory.file("quality/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    // Each module's files are parsed in parallel; the rules still run in order, so the findings are the same. Measured on
    // :app, :features-turns, :integrations-providers-codex and :core, Oct 9: 68.9 s of detekt one file at a time, 13.9 s
    // in parallel, with the same eight findings reported by both.
    parallel = true
}

// VERSIONS COME FROM THE CATALOG, never a literal (2026-07-29). detekt-formatting and junit-bom were
// pinned here as bare strings while `detekt` and `junit` already lived in libs.versions.toml, so a
// catalog bump left BOTH behind silently: detekt-formatting at a version the detekt plugin no longer
// matches, and junit-bom disagreeing with the platform the modules resolve. Nothing fails loudly —
// you get a skew, which is the failure mode a version catalog exists to make impossible.
//
// A precompiled script plugin gets no generated `libs` accessor, which is why the literals were here
// in the first place; VersionCatalogsExtension is the supported way to reach it from this context.
private val catalog =
    CatalogReader(extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs"))

// FORMATTING IS STANDALONE KTLINT, not detekt-formatting (2026-10-09). detekt 1.23.8 bundles ktlint 0.50, which cannot
// parse a Kotlin context-parameter clause: one rule crashes on it and another misreads its colon. The ktlint Gradle plugin
// runs the current ktlint on the same sources in the same `check`, with the rule list pinned in the root .editorconfig.
ktlint {
    version.set(catalog.version("ktlint"))
    // generated sources live under build/ and are not ours to format
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:${catalog.version("junit")}"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Gradle's 512m worker default intermittently kills the 1000-stream load test mid-gate
    // (worker dies -> bare java.io.EOFException, 2026-07-18 x2). 2g since the upstream client moved
    // from ktor CIO to the JDK HttpClient engine (CIO busy-spun the CPU) — the JDK engine holds more
    // per-connection state, so the 1000-stream CEILING test needs the extra heap. Real load is tens
    // of streams (far under 1g either way); this only funds the stress ceiling.
    maxHeapSize = "2g"
    // FOUR PROCESSORS FOR EVERY TEST JVM (V4-307). A local build can run in one scope capped at 512
    // tasks, shared by every test JVM of a --parallel ladder, and each JVM sizes its CPU-sized
    // pools (virtual-thread carriers, Netty event loops, coroutine workers) to the host's 32 cores and
    // grows them by starting native threads as work arrives. At a full scope that start fails:
    // UpstreamClientWriteStallTest's loop lost a carrier ("Failed to start the native thread for
    // java.lang.Thread "ForkJoinPool-1-worker-11"", pthread_create EAGAIN), and the request-write watch
    // riding on it never cut the stalled write. features-turns carried this bound alone since its
    // 1000-stream load gate hit the same EAGAIN; one module bounded was a class left open.
    jvmArgs("-XX:ActiveProcessorCount=4")
    // HERMETIC HOME. splice resolves its key store and credential files from SPLICE_CONFIG, then
    // XDG_CONFIG_HOME, then $HOME/.config (KeyStorePath.defaultPath, whose own comment already
    // promises "test rigs stay hermetic"). Nothing pointed those at the rig, so any test asserting
    // a credential is ABSENT read the OPERATOR's real ~/.config/splice/keys.toml: StatusCommandTest
    // and MultiProviderDaemonTest's DR-81 both failed on this machine against a genuine stored
    // OPENROUTER_API_KEY while passing on every machine without one. A suite that measures the
    // developer's home instead of its own fixtures is worse than red -- it is red somewhere else.
    val testHome = layout.buildDirectory.dir("test-home").get().asFile
    doFirst { testHome.resolve("config").mkdirs() }
    // The rig home is named after the cache key is taken (doFirst runs after the inputs are fingerprinted), so it does not key the task.
    doFirst { environment("XDG_CONFIG_HOME", testHome.resolve("config").absolutePath) }
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
    jvmArgumentProviders.add(
        splice.testing.MachineLocalArguments(provider { listOf("-Duser.home=${testHome.absolutePath}") }),
    )
    // HOME outranks user.home (UserHome.kt, V4-218), so the rig home is named in both: a test JVM that kept
    // the shell's HOME would resolve every ~/ path into the developer's real home again.
    doFirst { environment("HOME", testHome.absolutePath) }
    // A TEST THAT RETURNS A VALUE NEVER RUNS, AND NOW THAT FAILS THE MODULE'S OWN RUN. Kotlin makes the
    // shape easy (`fun x() = runBlocking { ... }` returns the block's last expression), and JUnit skips a
    // non-void @Test with a WARNING discovery issue, "must not return a value. It will not be executed."
    // At JUnit's default critical severity, ERROR, the run stays green: on 2026-09-25 KeysRouteTest ran
    // 3 of its 5 tests and its module passed. verifyTestDiscovery would have named the two at the gate,
    // but a module run is where an author reads a test, so WARNING is critical here: the engine refuses
    // to run and names the method.
    systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
    // A CI failure must carry its assertion MESSAGE, not only "AssertionFailedError at X.kt:274".
    // Gradle's default prints the location alone, so the two CI-only failures of the perf
    // telemetry integration arm (runs 33608202738 and 33928312116) left no way to read what was
    // logged instead of the expected line. Failed events only: a green run stays quiet.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
    // A RESULTS DIRECTORY LEFT MID-WRITE RECOVERS HERE, INSTEAD OF ENDING EVERY RUN AFTER IT.
    // gradle's reporter finishes by moving its in-progress file onto the final one
    // (SerializableTestResultStore$Writer.close: Files.move(in-progress-results-generic.bin,
    // results-generic.bin, REPLACE_EXISTING)), so an existing TARGET is harmless and a missing
    // SOURCE is fatal — NoSuchFileException at DefaultRootTestEventReporter.close, which names an
    // internal binary and nothing a reader can act on. Two gradle runs sharing one project
    // directory is how the source goes missing, and on 2026-10-10 that cost a seat three runs with
    // a different wrong diagnosis each time: a flake, then concurrency, then memory.
    //
    // ONE GRADLE AT A TIME IS THE WRAPPER'S JOB NOW (./gradlew routes itself through the slot), so
    // an in-progress file sitting here BEFORE this task runs belongs to no live run: it is what a
    // killed or collided run left behind. Clearing it costs this run nothing it was going to keep —
    // the run rewrites these reports anyway — and keeping it costs every run after.
    val resultsDir = reports.junitXml.outputLocation
    val binaryDir = binaryResultsDirectory
    val htmlDir = reports.html.outputLocation
    doFirst {
        val binary = binaryDir.get().asFile
        val leftMidWrite = binary.listFiles { file -> file.name.startsWith("in-progress-") }.orEmpty()
        if (leftMidWrite.isNotEmpty()) {
            val named = leftMidWrite.joinToString(", ") { it.name }
            // The XML directory is the binary directory's parent, so one delete covers both.
            val clearing = listOf(resultsDir.get().asFile, htmlDir.get().asFile)
            clearing.forEach { it.deleteRecursively() }
            val kept = clearing.filter { it.exists() }
            if (kept.isNotEmpty()) {
                throw GradleException(
                    "$path: a previous run left $named in $binary, which makes gradle's test reporter " +
                        "fail at close on every later run, and this could not be cleared. Delete it and " +
                        "run again:\n" + kept.joinToString("\n") { "    rm -rf $it" },
                )
            }
            logger.lifecycle("$path: cleared a results directory a previous run left mid-write ($named)")
        }
    }
}
