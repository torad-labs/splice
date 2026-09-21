// THE GRADLE WIRING for V4-68 (restructure plan §6.3, row "tests are discovered"). The checker
// itself is splice.discovery.TestDiscovery (build-logic/src/main/kotlin/splice/discovery/
// TestDiscovery.kt) — pure Kotlin, no Gradle types, proved against fixtures and @TempDir files by
// TestDiscoveryTest. This script is the other half: it resolves the Gradle project model into the
// File/String arguments TestDiscovery's functions take, and registers the task that runs them.
// Applied at the ROOT project only — like splice.gate-ladder, which is what wires
// verifyTestDiscovery into gateOfRecord itself (this file never touches gateOfRecord or
// ladder.json).
//
// POSITION IS LOAD-BEARING (measured against the original bun checker, carried into this port
// unchanged): verifyTestDiscovery depends on EVERY Test task because they PRODUCE the XML it
// reads, and it must read it in the same pass — the results directory is a shared,
// mutually-destructive observation. A SCOPED run of one test class in a module wipes every other
// class's XML in that module's results directory (62 of 63 files deleted mid-run, measured); a
// verifyTestDiscovery that read whatever XML happened to be lying around, rather than XML this
// exact graph just produced, would read that destruction as "never ran" for classes that ran
// perfectly well in some earlier, unrelated invocation.
import org.gradle.api.tasks.testing.Test
import splice.discovery.JUnitXml.scanModuleXml
import splice.discovery.SourceScan.scanModuleSources
import splice.discovery.TestDiscovery.audit
import splice.discovery.TestDiscovery.census
import splice.discovery.TestDiscovery.summaryLine

/** Every Test task of every subproject. The same shape as splice.gate-ladder's own
 *  `everyTestTask` — script plugins do not share top-level vals across files, so this is
 *  redeclared here rather than imported. Resolved when the graph is built, after every project is
 *  configured (see that file's own comment on why this must stay a provider). */
val everyTestTask = provider { subprojects.flatMap { it.tasks.withType<Test>() } }

/** module (Gradle project path) -> its src/test/kotlin directory — the DENOMINATOR the original
 *  covered with a MODULE_HOMES glob; a subproject's own conventional test source directory
 *  replaces it. Absent for a module that ships no Kotlin tests (:console) or none yet —
 *  TestDiscovery.scanModuleSources reads a missing directory as empty, never an error. */
val testSourceDirsByModule = provider {
    subprojects.associate { it.path to it.projectDir.resolve("src/test/kotlin") }
}

/** module -> every one of its Test tasks' own DECLARED junitXml output directory — read from each
 *  producer, never a shared glob (see the file header). Most subprojects have exactly one Test
 *  task ("test"); :app also has codeModePackagedTest, which reruns two classes against the
 *  packaged shadow jar into its own results directory — scanModuleXml merges every producer's
 *  rows for a module, keeping the LOWER count on a class both report. */
val xmlDirsByModule = provider {
    subprojects.associate { subproject ->
        subproject.path to subproject.tasks.withType<Test>().map { it.reports.junitXml.outputLocation.get().asFile }
    }
}

private fun scannedClasses() = testSourceDirsByModule.get().flatMap { (module, dir) -> scanModuleSources(dir, module) }
private fun observedXml() = xmlDirsByModule.get().mapValues { (_, dirs) -> scanModuleXml(dirs) }

tasks.register("verifyTestDiscovery") {
    group = "gate"
    description = "V4-68: every @Test/@ParameterizedTest method declared under a subproject's " +
        "src/test/kotlin must appear in that class's own JUnit XML. Depends on every Test task " +
        "of every subproject — see this file's header for why position is load-bearing."
    dependsOn(everyTestTask)
    // A verdict, never an artifact — the same rule the ladder legs declare (splice.gate-ladder).
    outputs.upToDateWhen { false }
    // Declared for LEGIBILITY (`gradle verifyTestDiscovery --info`, task graph inspection), not
    // for up-to-date checking: the line above already refuses to be up-to-date regardless.
    inputs.files(testSourceDirsByModule.map { it.values }).withPropertyName("testSourceDirectories").optional()
    inputs.files(xmlDirsByModule.map { it.values.flatten() }).withPropertyName("junitXmlDirectories").optional()
    doLast {
        val classes = scannedClasses()
        val xmlByModule = observedXml()
        val problems = audit(classes, xmlByModule)
        check(problems.isEmpty()) { "tests-are-discovered RED:\n  " + problems.joinToString("\n  ") }
        println(summaryLine(classes, xmlByModule))
    }
}

tasks.register("testDiscoveryReport") {
    group = "gate"
    description = "The tests-are-discovered census: every declared class against its observed " +
        "XML count, plus stale XML rows for classes no longer in source. Reads whatever is " +
        "currently in each module's test-results directory as-is — run verifyTestDiscovery " +
        "first for a fresh one. Never a dependency of gateOfRecord: this is the --report verb, " +
        "not the gate."
    outputs.upToDateWhen { false }
    inputs.files(testSourceDirsByModule.map { it.values }).withPropertyName("testSourceDirectories").optional()
    inputs.files(xmlDirsByModule.map { it.values.flatten() }).withPropertyName("junitXmlDirectories").optional()
    doLast {
        println(census(scannedClasses(), observedXml()))
    }
}
