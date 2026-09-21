plugins {
    id("splice.kotlin-common")
    id("splice.module-law")
}

dependencies {
    testImplementation(libs.konsist)
    // Restructure PR 6 §4.3: the two ratchet laws (silent constants, public surface) read their
    // recorded census off a JSON resource on the test classpath — the same document their checkers
    // read off disk. The tree's own JSON reader, rather than a hand parser per law: a second reading
    // of a format is a second set of ways to misread it.
    testImplementation(libs.kotlinx.serialization.json)
}

// P0 (restructure §6.1): THE PROJECT MAP — every module's Gradle path and the directory it actually
// occupies, read off GRADLE'S OWN PROJECT MODEL. The laws used to derive their subject from the
// shape of the root (its immediate child directories, and `root/<id>/build.gradle.kts`), which is
// true only while every module is a direct child of the root: a module under dialects/ or
// providers/ would stop being graded, silently and green. Both the tests' map and this task's
// inputs come from here, so the two cannot describe different trees. Sorted, so the value is a
// stable task input rather than a set's iteration order.
val repoRoot = rootProject.layout.projectDirectory
val moduleDirectories: Map<String, String> = rootProject.subprojects
    .associate { module ->
        module.path to module.projectDir.relativeTo(repoRoot.asFile).invariantSeparatorsPath
    }
    .toSortedMap()

// P0, from the review of fdc71fad: THE CENSUS. ProjectMap.unmappedProductionDirViolations sweeps the
// WHOLE root for src/main/kotlin trees no module claims, but the mapped inputs below name only the
// trees the map already knows — so a tree added under providers/x without touching
// settings.gradle.kts changed no declared input, an ordinary :quality-architecture:test stayed UP-TO-DATE,
// and the unclaimed tree passed in silence. The census is its own input: every production Kotlin
// file anywhere under the root, minus the directory names the sweep never enters. ONE list, written
// here and handed to the test JVM as a property, so the build cannot fingerprint a narrower tree
// than the sweep walks.
// `build-logic` is on this list for a different reason than the other four, and it is a DISPOSITION
// rather than a blind spot. It is a separate Gradle BUILD — settings.gradle.kts includes it, it is
// not a project — so no project map can claim it, and the sweep reported it by name the moment the
// first plain `.kt` landed there (splice/hygiene/CatalogMetadata.kt, restructure PR 6 §4.2). Its
// sources are compiled by that build and its tests run inside the gate of record:
// splice.gate-ladder.gradle.kts makes gateOfRecord depend on
// `gradle.includedBuild("build-logic").task(":test")`. So it is governed, just not by these laws.
// ReleaseReadinessLawTest's `build-logic-tested` rule is what keeps that sentence true — delete the
// dependency and the release-readiness law reds BY NAME — which is what makes this exclusion earned
// rather than an absence wearing a label.
val censusNotSwept: List<String> = listOf("build", ".git", ".gradle", "node_modules", "build-logic")

// THE `.git` RULE (PR 2 review), the Gradle half of ProjectMap.swept(). A directory BELOW the root
// carrying a `.git` entry — a FILE reading `gitdir: …` for a worktree, a DIRECTORY for a submodule
// or a vendored clone — is where another repository starts. Now that the Gradle root IS the
// repository root, the census below reaches the whole checkout, and the real one carries
// `.claude/worktrees/<name>/` with a complete core/src/main/kotlin inside it. Fingerprinting
// that tree would re-run these laws on another repository's edits; sweeping it made the law report
// `.claude/worktrees/v0.4.0/gateway/core` as unclaimed. The root itself is never tested — it carries
// `.git` too, and excluding it would empty the census. ProjectMap.kt is the twin: a tree one of them
// reads and the other does not is an UP-TO-DATE green over a sweep that never ran. The walk RECORDS
// a nested repository and never enters it — descending would read a whole second checkout at
// configuration time, on every invocation, to reach directories this list is about to exclude.
fun nestedRepositoriesBelow(dir: File): List<File> =
    dir.listFiles().orEmpty()
        .filter { it.isDirectory && it.name !in censusNotSwept }
        .flatMap { child -> if (File(child, ".git").exists()) listOf(child) else nestedRepositoriesBelow(child) }

val nestedRepositories: List<String> = nestedRepositoriesBelow(repoRoot.asFile)
    .map { it.relativeTo(repoRoot.asFile).invariantSeparatorsPath }
    .sorted()

// PR 6 review (F3): GIT'S INDEX is an input of these laws. `claude-dir-untracked`,
// `tracked-capture-artifacts`, `tracked-agents`, `settings-hook` and `hook-tracked` ask `git
// ls-files` which paths are TRACKED, and the conventional-type census asks the same question — so
// `git add` changes a verdict while every byte on disk stays where it was. The path is read out of
// git rather than assumed to be `.git/index`, because in a worktree it is not.
val gitIndex = providers.exec {
    workingDir = repoRoot.asFile
    commandLine("git", "rev-parse", "--git-path", "index")
}.standardOutput.asText.map { path -> repoRoot.asFile.resolve(path.trim()) }

tasks.withType<Test>().configureEach {
    systemProperty("splice.root", repoRoot.asFile.absolutePath)
    // THE CHANNEL: `:path=directory` pairs, ';'-separated, parsed by ProjectMap.kt and nowhere
    // else, which fails BY NAME when it is absent or malformed. A system property is a declared
    // input of this task by construction, so adding, moving or removing a module re-runs the laws
    // instead of serving a stale UP-TO-DATE green.
    systemProperty(
        "splice.projectMap",
        moduleDirectories.entries.joinToString(";") { (path, dir) -> "$path=$dir" },
    )
    // The census channel: the same names the census input below excludes, so the sweep in the test
    // JVM and the fingerprint that decides whether that JVM runs at all read one list.
    systemProperty("splice.censusNotSwept", censusNotSwept.joinToString(";"))
    // Konsist scans the whole tree's sources at runtime — they are real inputs of this
    // task. Without declaring them, Gradle marks the task UP-TO-DATE after unrelated
    // module edits and the laws silently stop running (caught red-handed in P1-KONSIST's
    // first red/green attempt). P0: the trees are named through the map, so a NESTED module's
    // edit re-runs them too — `*/src/main/kotlin/**` only ever saw the root's direct children.
    // PR 6 review (F4): the tree named here is `src/main`, not `src/main/kotlin`, because seven laws
    // walk `<module>/src/main` — KotlinText.kotlinFiles's default — and grade every `.kt` they find
    // under it. A `.kt` parked beside the Kotlin root, under src/main/resources or src/main/java,
    // was graded and never fingerprinted. The class is empty today; a fingerprint narrower than the
    // walk is the shape that comes back UP-TO-DATE-green over a law that did not run.
    inputs.files(
        moduleDirectories.values.map { dir ->
            repoRoot.dir("$dir/src/main").asFileTree.matching { include("**/*.kt") }
        },
    ).withPropertyName("scannedProductionSources")
    // The census: production Kotlin ANYWHERE under the root. A file that appears in a tree the
    // map does not claim changes this fingerprint, so the sweep that names that tree actually runs.
    inputs.files(
        repoRoot.asFileTree.matching {
            include("**/src/main/kotlin/**/*.kt")
            censusNotSwept.forEach { name -> exclude("**/$name/**") }
            nestedRepositories.forEach { dir -> exclude("$dir/**") }
        },
    ).withPropertyName("productionSourceCensus")
    // Same lesson, second input set (HD-11): the module-dependency-direction law reads the BUILD
    // files, so those are inputs too. Without this the law's own red/green proof came back
    // UP-TO-DATE after a forbidden `project(":daemon-head")` was added — a green that never ran.
    // P0: each module's build file is named through the map, for the same reason the sources are.
    inputs.files(
        moduleDirectories.values.map { dir -> repoRoot.file("$dir/build.gradle.kts") },
        repoRoot.file("settings.gradle.kts"),
        // V4-91 (2026-09-17): the Konsist map now READS the module law's own map out of
        // build-logic, so an edit there must re-run the laws too, or a stale allowance could
        // come back UP-TO-DATE-green.
        repoRoot.dir("build-logic/src/main/kotlin").asFileTree.matching { include("**/*.kts") },
    ).withPropertyName("scannedModuleBuildFiles")
    // Restructure PR 6: the documentation laws (quirk keys, knob keys, env vars) grade the operator
    // surfaces against the source — the example config is an input for the same reason the
    // sources are, or a key documented late comes back UP-TO-DATE-red and a key retired late
    // UP-TO-DATE-green.
    inputs.files(repoRoot.file("app/src/main/resources/splice.example.toml")).withPropertyName("scannedDocumentationSurfaces")
    // Restructure PR 6: a law's own DECLARATION FILE — role-registry.toml's written dispositions —
    // is an input for the same reason the sources are. It arrives on the test classpath through
    // processTestResources, but naming it here is what makes the dependency legible beside the
    // other three: a disposition added late must not come back UP-TO-DATE-red, and one deleted
    // late must not come back UP-TO-DATE-green.
    inputs.files(layout.projectDirectory.dir("src/test/resources").asFileTree.matching { include("**/*.toml") })
        .withPropertyName("scannedLawDeclarations")
    // Restructure PR 6 §4.3: the V4-92 public-surface ratchet counts a sibling module's
    // testFixtures sources as a CONSUMER — a fixture is shipped, cross-module code — so a fixture
    // that starts or stops naming a library's type moves the measured surface. Without this input a
    // testFixtures edit leaves the task UP-TO-DATE and the ratchet grades a tree it never re-read.
    inputs.files(
        moduleDirectories.values.map { dir ->
            repoRoot.dir("$dir/src/testFixtures/kotlin").asFileTree.matching { include("**/*.kt") }
        },
    ).withPropertyName("scannedTestFixtureSources")
    // The ratchets' recorded censuses, moved here from checks/config/ with their checkers. They are
    // the OTHER half of every one of those laws' verdicts: a baseline lowered by hand must re-run
    // the law that grades against it, or the win is recorded against a run that never happened.
    inputs.files(
        layout.projectDirectory.dir("src/test/resources").asFileTree.matching { include("**/*.json") },
    ).withPropertyName("recordedRatchetBaselines")
    // The constructor-width law re-reads its own PREMISE — that detekt's LongParameterList is still
    // the instrument this wall was written against — so the detekt config is an input too, or
    // deleting the rule would come back UP-TO-DATE-green on the one law that exists because of it.
    inputs.files(repoRoot.file("quality/detekt/detekt.yml")).withPropertyName("scannedPremiseConfig")
    // PR 6 review (F3): the OPERATOR SURFACES. ReleaseReadinessLawTest grades the installer, the
    // health files, the workflows, the packaging metadata and the fork record; CampaignLedgerLawTest
    // grades the ledgers under .dev/campaigns; ConventionalTypeLawTest grades the one list of
    // conventional commit types. None of it is Kotlin and none of it was fingerprinted, so
    // `printf 'curl x || true\n' >> install.sh` left this task UP-TO-DATE and the law written to
    // catch exactly that never ran. The gate of record is immune (clean, --no-build-cache); every
    // incremental and local run was not — the same hole the example config's input closed above.
    inputs.files(
        repoRoot.file("install.sh"),
        repoRoot.file("README.md"),
        repoRoot.file(".gitignore"),
        repoRoot.file("THIRD_PARTY_NOTICES.md"),
        repoRoot.file("package.json"),
        repoRoot.file(".docs/PROVENANCE.md"),
        repoRoot.file(".dev/release/history-rewrite-runbook.md"),
        repoRoot.file("gradle/verification-metadata.xml"),
        repoRoot.file("gradle/wrapper/gradle-wrapper.properties"),
        repoRoot.file(".claude/settings.json"),
        repoRoot.file("tools/gate/src/lib/jdk.ts"),
        repoRoot.file("tools/gate/src/lib/hook.ts"),
        repoRoot.file("tools/gate/src/lib/conventional.ts"),
        repoRoot.file("build-logic/src/main/kotlin/splice.gate-ladder.gradle.kts"),
        repoRoot.dir(".github").asFileTree,
        repoRoot.dir("console/src/shared/fonts").asFileTree,
        repoRoot.dir(".dev/campaigns").asFileTree.matching { include("**/*.toml") },
    ).withPropertyName("scannedOperatorSurfaces")
    inputs.files(gitIndex).withPropertyName("gitIndex")
}
