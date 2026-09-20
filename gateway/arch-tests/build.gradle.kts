plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    testImplementation(libs.konsist)
}

// P0 (restructure §6.1): THE PROJECT MAP — every module's Gradle path and the directory it actually
// occupies, read off GRADLE'S OWN PROJECT MODEL. The laws used to derive their subject from the
// shape of the root (its immediate child directories, and `root/<id>/build.gradle.kts`), which is
// true only while every module is a direct child of the root: a module under dialects/ or
// providers/ would stop being graded, silently and green. Both the tests' map and this task's
// inputs come from here, so the two cannot describe different trees. Sorted, so the value is a
// stable task input rather than a set's iteration order.
val gatewayRoot = rootProject.layout.projectDirectory
val moduleDirectories: Map<String, String> = rootProject.subprojects
    .associate { module ->
        module.path to module.projectDir.relativeTo(gatewayRoot.asFile).invariantSeparatorsPath
    }
    .toSortedMap()

tasks.withType<Test>().configureEach {
    systemProperty("gateway.root", gatewayRoot.asFile.absolutePath)
    // THE CHANNEL: `:path=directory` pairs, ';'-separated, parsed by ProjectMap.kt and nowhere
    // else, which fails BY NAME when it is absent or malformed. A system property is a declared
    // input of this task by construction, so adding, moving or removing a module re-runs the laws
    // instead of serving a stale UP-TO-DATE green.
    systemProperty(
        "splice.projectMap",
        moduleDirectories.entries.joinToString(";") { (path, dir) -> "$path=$dir" },
    )
    // Konsist scans the whole tree's sources at runtime — they are real inputs of this
    // task. Without declaring them, Gradle marks the task UP-TO-DATE after unrelated
    // module edits and the laws silently stop running (caught red-handed in P1-KONSIST's
    // first red/green attempt). P0: the trees are named through the map, so a NESTED module's
    // edit re-runs them too — `*/src/main/kotlin/**` only ever saw the root's direct children.
    inputs.files(
        moduleDirectories.values.map { dir ->
            gatewayRoot.dir("$dir/src/main/kotlin").asFileTree.matching { include("**/*.kt") }
        },
    ).withPropertyName("scannedProductionSources")
    // Same lesson, second input set (HD-11): the module-dependency-direction law reads the BUILD
    // files, so those are inputs too. Without this the law's own red/green proof came back
    // UP-TO-DATE after a forbidden `project(":gateway")` was added — a green that never ran.
    // P0: each module's build file is named through the map, for the same reason the sources are.
    inputs.files(
        moduleDirectories.values.map { dir -> gatewayRoot.file("$dir/build.gradle.kts") },
        gatewayRoot.file("settings.gradle.kts"),
        // V4-91 (2026-09-17): the Konsist map now READS the module law's own map out of
        // build-logic, so an edit there must re-run the laws too, or a stale allowance could
        // come back UP-TO-DATE-green.
        gatewayRoot.dir("build-logic/src/main/kotlin").asFileTree.matching { include("**/*.kts") },
    ).withPropertyName("scannedModuleBuildFiles")
}
