// THE GATE LADDER — every leg of the gate of record as a Gradle task, under one lifecycle task,
// `gateOfRecord`, entered through `bun tools/gate run` (restructure plan §6.3; checks/gate.sh until
// PR 5). Applied at the ROOT project only.
//
// THE LEGS ARE DATA: tools/gate/config/ladder.json names each leg's task, its argv and WHY it
// exists, and this plugin registers one Exec task per row. The table is data rather than Kotlin so
// that a checker can read the same rows without running Gradle — tools/gate/test/run.test.ts does
// exactly that, proving every row is a real leg with a reason and an argv whose target exists (it
// caught two legs still pointing into checks/ on 2026-09-21, after the move). The concentration
// leg's own routing check retired with the checker it routed, for the reason configguard.ts:11-13
// records — and `verifyLadder` proves the registration: every row is an Exec task with that
// argv inside gateOfRecord's dependency closure, so a row the plugin dropped or a dependsOn somebody
// removed is a red leg, not a silently narrower gate.
//
// NEVER UP-TO-DATE, ON PURPOSE (a stated deviation from P10), except a leg that reads the fat jar. A leg's output is a
// verdict, not an artifact: the gate of record runs `clean` with `--no-build-cache` because Kotlin's compile avoidance
// once handed it a mixture of two source states, and an up-to-date verdict is the same hole one layer up. Fingerprinting
// the tree every checker reads (all of it, plus git) would also cost more than most legs. So a leg with no jar dependency
// declares no inputs and refuses up-to-date. A leg that depends on `:app:shadowJar` declares what its verdict reads: the
// jar's own output (a rebuilt jar re-runs it) and the e2e files its row names (an edit re-runs it). A stamp records each
// run, so the leg has an output for gradle to judge.
//
// WHAT IS NOT HERE: the release rehearsal (`bun tools/release verify`). It builds and stages a
// release through the slot, and the slot is held by the gate for the whole of this graph — a
// nested run would wait on its own lock. `bun tools/gate run` runs it AFTER the graph, with the
// slot released. The Kotlin modules' `check` tasks carry detekt, the unit suites and the module laws.
import org.gradle.api.tasks.testing.Test
import splice.ladder.LadderLeg
import splice.ladder.LadderTable

// The gate's Gradle-native checks, registered by their own plugins and depended on below: the
// dependency hygiene of §4.2 (catalogMetadataSync) and build-logic's own test suite, which carries
// those checks' red proofs (restructure PR 6).
plugins {
    id("splice.dependency-hygiene")
}

val ladderPath = "tools/gate/config/ladder.json"

val legs: List<LadderLeg> = LadderTable.readAll(layout.projectDirectory.file(ladderPath).asFile)
require(legs.isNotEmpty()) { "$ladderPath names no legs: a gate with no legs is not a gate" }

/** Every Test task of every subproject, resolved when the graph is built, after every project is configured. */
val everyTestTask = provider { subprojects.flatMap { it.tasks.withType<Test>() } }

val repository = layout.projectDirectory

val legTasks = legs.map { leg ->
    val name = leg.task
    val dependsOnTasks = leg.dependsOn
    val stamp = layout.buildDirectory.file("gate/$name.stamp")
    tasks.register<Exec>(name) {
        group = "gate"
        description = leg.why
        workingDir = rootDir
        commandLine(leg.command)
        dependsOnTasks.forEach { dependsOn(it) }
        if (leg.afterAllTests) dependsOn(everyTestTask)
        if (leg.readsJar()) {
            // The verdict reads the jar and the files the row's `inputs` globs name. Both are inputs, so an unchanged tree stays
            // UP-TO-DATE, and a rebuilt jar or an edited e2e file re-runs the leg. The stamp is the output gradle judges.
            val e2eGlobs = leg.files.inputs
            require(e2eGlobs.isNotEmpty()) { "$name reads the jar and names no inputs in $ladderPath" }
            // The fat jar's task is looked up when the inputs are read, after every project is configured: :app is not
            // configured yet while this root plugin is applied, so a lookup here would fail the whole configuration.
            inputs.files(provider { project(":app").tasks.named("shadowJar").get() }).withPropertyName("fatJar")
            // The row's globs are expanded by git (tracked, plus untracked and not ignored), never by walking the tree: a walk reads
            // ignored directories and throws on a dangling link under them.
            inputs.files(
                splice.lawsuite.ReadSet.globbedProvider(project, e2eGlobs).map { files -> files.map { rootDir.resolve(it) } },
            ).withPropertyName("ladderInputs")
            outputs.file(stamp)
            doLast { stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("$name\n") }
        } else {
            outputs.upToDateWhen { false }
        }
        // The one file a leg writes and owns is removed as the leg starts: a rerun of the leg must not find its own
        // previous output. Only the file the row names is removed, never a directory, and a failed removal stops the leg.
        val files = leg.files
        doFirst { files.prepare(rootDir) }
    }
}

// THE LAW SUITES, one task for the pre-push gate. Each suite grades files its own sources do not hold, and declares them as the
// inputs of its law task (`lawTest`, from splice.law-suite), so gradle runs a suite only when one of those inputs changed or on
// its first run. The unit suites are not here: a unit suite reruns only when its own classpath changes. The law tasks are not
// listed here: every project that applies splice.law-suite adds its own `lawTest` to this task. The one name below is the
// architecture module, whose whole `test` task is laws and so applies no tag.
tasks.register("lawSuites") {
    group = "gate"
    description =
        "Every law suite that reads outside its own sources. Pre-push requests this one task; gradle's " +
        "up-to-date check skips each suite whose declared inputs did not change."
    dependsOn(":quality-architecture:test")
}

val gateOfRecord = tasks.register("gateOfRecord") {
    group = "gate"
    description =
        "The gate of record: every module's check and every leg of " +
        "$ladderPath. Enter through `bun tools/gate run` (JDK 21, the slot, clean, no build cache)."
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn(legTasks)
    dependsOn("verifyLadder")
    dependsOn("catalogMetadataSync")
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
    dependsOn(gradle.includedBuild("build-logic").task(":detekt"))
}

// THE PROOF IS TAKEN WHEN THE GRAPH IS READY, NEVER DURING EXECUTION. The first cut walked
// `task.taskDependencies.getDependencies(task)` inside verifyLadder's action, and a string
// dependency (`":app:shadowJar"`) resolves through BuildScopedTaskResolver.ensureProjectsConfigured,
// which wants the build's state lock — held for the whole execution phase. Measured 2026-09-21,
// twice (the second time behind a `by lazy` that only moved the same walk): the build hung at
// `> Task :verifyLadder` with the worker parked in withStateLock. At taskGraph.whenReady the graph
// is Gradle's own answer to "what will run", and TaskExecutionGraph.getDependencies reads the
// nodes it already resolved; nothing here resolves a dependency of its own.
//
// The proof exists only under gateOfRecord: standalone, the legs are not in the graph and
// getDependencies has nothing to read, so verifyLadder says so and fails rather than pass on an
// empty set.
var ladderProblems: List<String>? = null
gradle.taskGraph.whenReady {
    val root = gateOfRecord.get()
    if (!hasTask(root)) return@whenReady
    val direct = getDependencies(root)
    ladderProblems = legs.mapNotNull { leg ->
        val name = leg.task
        val command = leg.command
        val task = tasks.findByName(name)
        when {
            task == null -> "$name: named in $ladderPath and registered by nothing"
            task !is Exec -> "$name: is not an Exec task"
            task.commandLine != command -> "$name: runs ${task.commandLine} where $ladderPath says $command"
            task !in direct -> "$name: gateOfRecord does not depend on it, so nothing runs this leg"
            !hasTask(task) -> "$name: excluded from this graph, which makes the gate narrower than $ladderPath"
            else -> null
        }
    }
}

tasks.register("verifyLadder") {
    group = "gate"
    description =
        "Proves $ladderPath against the graph: every row is an Exec task with that argv that gateOfRecord " +
        "depends on and this graph schedules. Meaningful only under gateOfRecord."
    outputs.upToDateWhen { false }
    doLast {
        val problems = checkNotNull(ladderProblems) {
            "verifyLadder proves the ladder under gateOfRecord, which is not in this build's graph: " +
                "run `bun tools/gate run` (or gateOfRecord)"
        }
        check(problems.isEmpty()) { "the gate ladder disagrees with $ladderPath:\n  " + problems.joinToString("\n  ") }
        logger.lifecycle(
            "verifyLadder: ${legs.size} leg(s) registered from $ladderPath, " +
                "every one a dependency of gateOfRecord and scheduled in this graph",
        )
    }
}
