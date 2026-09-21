// THE GATE LADDER — every leg of the gate of record as a Gradle task, under one lifecycle task,
// `gateOfRecord`, entered through `bun tools/gate run` (restructure plan §6.3; checks/gate.sh until
// PR 5). Applied at the ROOT project only.
//
// THE LEGS ARE DATA: tools/gate/config/ladder.json names each leg's task, its argv and WHY it
// exists, and this plugin registers one Exec task per row. The table is data rather than Kotlin so
// that a checker can read the same rows without running Gradle — checks/config/concentration-leg-
// routed.ts's forward half asks that file whether the concentration leg is routed, as it used to
// ask gate.sh — and `verifyLadder` proves the registration: every row is an Exec task with that
// argv inside gateOfRecord's dependency closure, so a row the plugin dropped or a dependsOn somebody
// removed is a red leg, not a silently narrower gate.
//
// NEVER UP-TO-DATE, ON PURPOSE (a stated deviation from P10). A leg's output is a verdict, not an
// artifact: the gate of record runs `clean` with `--no-build-cache` because Kotlin's compile
// avoidance once handed it a mixture of two source states, and an up-to-date verdict is the same
// hole one layer up. Fingerprinting the tree every checker reads (all of it, plus git) would also
// cost more than most legs. So each leg declares no inputs and refuses up-to-date.
//
// WHAT IS NOT HERE: the release rehearsal (`bun tools/release verify`). It builds and stages a
// release through the slot, and the slot is held by the gate for the whole of this graph — a
// nested run would wait on its own lock. `bun tools/gate run` runs it AFTER the graph, with the
// slot released. The console's lint and
// vitest suite are `:console:lint` and `:console:test`, tasks of their own module, and the Kotlin
// modules' `check` tasks carry detekt, the unit suites and the module laws.
import groovy.json.JsonSlurper
import org.gradle.api.tasks.testing.Test

// The gate's Gradle-native checks, registered by their own plugins and depended on below: the
// dependency hygiene of §4.2 (catalogMetadataSync) and build-logic's own test suite, which carries
// those checks' red proofs (restructure PR 6).
plugins {
    id("splice.dependency-hygiene")
}

val ladderPath = "tools/gate/config/ladder.json"

@Suppress("UNCHECKED_CAST")
val legs: List<Map<String, Any?>> =
    ((JsonSlurper().parse(layout.projectDirectory.file(ladderPath).asFile) as Map<String, Any?>)["legs"] as List<Map<String, Any?>>)
require(legs.isNotEmpty()) { "$ladderPath names no legs — a gate with no legs is not a gate" }

/** Every Test task of every subproject, resolved when the graph is built, after every project is configured. */
val everyTestTask = provider { subprojects.flatMap { it.tasks.withType<Test>() } }

@Suppress("UNCHECKED_CAST")
val legTasks = legs.map { leg ->
    val name = leg["task"] as String
    val command = leg["command"] as List<String>
    tasks.register<Exec>(name) {
        group = "gate"
        description = leg["why"] as String
        workingDir = rootDir
        commandLine(command)
        outputs.upToDateWhen { false }
        (leg["dependsOn"] as List<String>?)?.forEach { dependsOn(it) }
        if (leg["afterAllTests"] == true) dependsOn(everyTestTask)
        (leg["creates"] as String?)?.let { dir -> doFirst { rootDir.resolve(dir).mkdirs() } }
    }
}

val gateOfRecord = tasks.register("gateOfRecord") {
    group = "gate"
    description =
        "The gate of record: every module's check, the console's lint and tests, and every leg of " +
            "$ladderPath. Enter through `bun tools/gate run` (JDK 21, the slot, clean, no build cache)."
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn(":console:lint", ":console:test")
    dependsOn(legTasks)
    dependsOn("verifyLadder")
    dependsOn("catalogMetadataSync")
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
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
@Suppress("UNCHECKED_CAST")
var ladderProblems: List<String>? = null
gradle.taskGraph.whenReady {
    val root = gateOfRecord.get()
    if (!hasTask(root)) return@whenReady
    val direct = getDependencies(root)
    ladderProblems = legs.mapNotNull { leg ->
        val name = leg["task"] as String
        val command = leg["command"] as List<String>
        val task = tasks.findByName(name)
        when {
            task == null -> "$name: named in $ladderPath and registered by nothing"
            task !is Exec -> "$name: is not an Exec task"
            task.commandLine != command -> "$name: runs ${task.commandLine} where $ladderPath says $command"
            task !in direct -> "$name: gateOfRecord does not depend on it — a leg nothing runs"
            !hasTask(task) -> "$name: excluded from this graph — a gate narrower than $ladderPath"
            else -> null
        }
    }
}

tasks.register("verifyLadder") {
    group = "gate"
    description = "Proves $ladderPath against the graph: every row is an Exec task with that argv that gateOfRecord depends on and this graph schedules. Meaningful only under gateOfRecord."
    outputs.upToDateWhen { false }
    doLast {
        val problems = checkNotNull(ladderProblems) {
            "verifyLadder proves the ladder under gateOfRecord, which is not in this build's graph — run `bun tools/gate run` (or gateOfRecord)"
        }
        check(problems.isEmpty()) { "the gate ladder disagrees with $ladderPath:\n  " + problems.joinToString("\n  ") }
        println("verifyLadder: ${legs.size} leg(s) registered from $ladderPath, every one a dependency of gateOfRecord and scheduled in this graph")
    }
}
