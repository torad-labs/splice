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
val repositoryRoot = rootDir
val appProject = provider { project(":app") }

val legTasks = legs.map { leg ->
    // Locals, not the script's own fields: a lambda that names a script field holds the script, and the configuration cache refuses it.
    val root = repositoryRoot
    val app = appProject
    val tests = everyTestTask
    val name = leg.task
    val dependsOnTasks = leg.dependsOn
    val stamp = layout.buildDirectory.file("gate/$name.stamp")
    tasks.register<Exec>(name) {
        group = "gate"
        description = leg.why
        workingDir = root
        commandLine(leg.command)
        dependsOnTasks.forEach { dependsOn(it) }
        if (leg.afterAllTests) dependsOn(tests)
        if (leg.fresh) {
            outputs.upToDateWhen { false }
        } else {
            // The verdict is a function of the files the row's `inputs` globs name (and the fat jar, for a leg that reads it), so an
            // unchanged tree stays UP-TO-DATE and an edit to any of them re-runs the leg. The stamp is the output gradle judges.
            val inputGlobs = leg.files.inputs
            require(inputGlobs.isNotEmpty()) { "$name names no inputs in $ladderPath" }
            if (leg.readsJar()) {
                // The fat jar's task is looked up when the inputs are read, after every project is configured: :app is not
                // configured yet while this root plugin is applied, so a lookup here would fail the whole configuration.
                inputs.files(app.map { it.tasks.named("shadowJar").get().outputs.files }).withPropertyName("fatJar")
            }
            // The row's globs are expanded by git (tracked, plus untracked and not ignored), never by walking the tree: a walk reads
            // ignored directories and throws on a dangling link under them.
            val matched = splice.lawsuite.ReadSet.globbedProvider(project, inputGlobs)
            inputs.files(
                matched.map { files -> files.map { root.resolve(it) } },
            ).withPropertyName("ladderInputs")
            outputs.file(stamp)
            doLast { stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("$name\n") }
        }
        // The one file a leg writes and owns is removed as the leg starts: a rerun of the leg must not find its own
        // previous output. Only the file the row names is removed, never a directory, and a failed removal stops the leg.
        val files = leg.files
        doFirst { files.prepare(root) }
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
    dependsOn(gradle.includedBuild("build-logic").task(":ktlintCheck"))
}

// THE PROOF IS TAKEN WHEN THE BUILD IS CONFIGURED, NEVER DURING EXECUTION. Reading a task's dependencies during execution wants the
// build's state lock, which is held for the whole execution phase (the build hung at `> Task :verifyLadder`, measured 2026-09-21), and
// project access during execution is not allowed under the configuration cache at all. The answer is a value read once, after every
// task is registered, and the task only reports it. A leg a command line excludes with `-x` is not seen here.
val ladderProblems = provider {
    val root = gateOfRecord.get()

    // The dependencies gateOfRecord was DECLARED with, read as names. Resolving them to tasks (taskDependencies.getDependencies) walks
    // the included build's tasks and the state of every project, which no phase of the build allows from here.
    val direct = buildSet {
        val pending = ArrayDeque<Any?>(root.dependsOn)
        while (pending.isNotEmpty()) {
            when (val entry = pending.removeFirst()) {
                is String -> add(entry)
                is Iterable<*> -> pending.addAll(entry)
                is TaskProvider<*> -> add(entry.name)
                is Task -> add(entry.name)
            }
        }
    }
    legs.mapNotNull { leg ->
        val name = leg.task
        val task = tasks.findByName(name)
        when {
            task == null -> "$name: named in $ladderPath and registered by nothing"
            task !is Exec -> "$name: is not an Exec task"
            task.commandLine != leg.command -> "$name: runs ${task.commandLine} where $ladderPath says ${leg.command}"
            name !in direct -> "$name: gateOfRecord does not depend on it, so nothing runs this leg"
            else -> null
        }
    }
}

// A provider handed to the task as an input is calculated when Gradle validates that task, during execution, and resolving
// gateOfRecord's dependencies there waits for the very lock the execution phase holds: the full gate stood at "> Task :verifyLadder"
// for 50 minutes on 2026-10-10 with every worker in configureProjects (jstack of the daemon). The value is therefore taken here,
// once every project is evaluated and before any task runs, into a property the task only reads.
val ladderProblemList = objects.listProperty<String>()
gradle.projectsEvaluated { ladderProblemList.set(ladderProblems.get()) }

tasks.register("verifyLadder") {
    group = "gate"
    description =
        "Proves $ladderPath against the build: every row is an Exec task with that argv that gateOfRecord depends on."
    val problems = ladderProblemList
    val legCount = legs.size
    val table = ladderPath
    inputs.property("ladderProblems", problems)
    doLast {
        check(problems.get().isEmpty()) {
            "the gate ladder disagrees with $table:\n  " + problems.get().joinToString("\n  ")
        }
        logger.lifecycle(
            "verifyLadder: $legCount leg(s) registered from $table, every one a dependency of gateOfRecord",
        )
    }
}
