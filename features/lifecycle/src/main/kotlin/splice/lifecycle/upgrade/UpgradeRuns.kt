// NEW: V4-220 item 4 (2026-09-25) — `splice upgrade` started from the console.
//
// IT CANNOT RUN INSIDE THE DAEMON: it replaces the daemon's own jar and restarts it. So it runs in its
// own transient user scope (UpgradeRunLauncher: `systemd-run --user --scope`), outside the daemon's
// cgroup, and outlives the restart it performs: the user unit's restart where one runs this install,
// the restart verb's stop and cold start otherwise, exactly as `splice upgrade` in a terminal.
//
// WHAT THE CONSOLE POLLS IS ON DISK, not in the daemon's memory. A run's directory holds its argv, its
// output, the pid of the shell that runs it and, once it ends, its exit code, so the daemon that comes
// back on the new jar reports the run the old one started.
//
// THE CONSOLE NEVER SENDS --now. The run waits for every turn, then for a compaction that started while
// the release activated (UpgradeDaemon.restart): an upgrade from the console never costs a compaction.
package splice.lifecycle.upgrade

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal const val RUN_FILE = "run.json"
internal const val RUN_OUTPUT = "output.log"
internal const val RUN_PID = "pid"
internal const val RUN_EXIT = "exit"

// why: a run the shell has not yet written its pid for is still starting; the launcher waits a few
// seconds for that pid, and a shell that has not written it in this long never started.
private const val START_GRACE_MS = 30_000L

// why: the console shows the run's progress, not its archive: the upgrade prints a few dozen lines, and
// this bounds a run that printed far more (a doctor report) without cutting a normal one.
private const val OUTPUT_LINES = 200

// why: the newest runs are kept for the console and the operator to read after the fact; older ones go.
private const val KEPT_RUNS = 5
private val ANSI = Regex("\u001B\\[[0-9;]*m")

/** What the console asked for: a release (latest when [to] is null), or the previous one. */
public data class UpgradeRequest(val to: String?, val rollback: Boolean)

/** Where a run stands, read off its directory. */
public enum class UpgradeRunState(public val wire: String) {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),

    /** The shell that ran it is gone and left no exit code: killed, or the machine went down. */
    LOST("lost"),
}

public data class UpgradeRunView(
    val id: String,
    val args: List<String>,
    val startedAtMillis: Long,
    val state: UpgradeRunState,
    val exitCode: Int?,
    val output: List<String>,
)

public sealed class UpgradeRunStart {
    public data class Started(val run: UpgradeRunView) : UpgradeRunStart()

    /** Another run is still going, or starting; nothing new was started. */
    public data object Busy : UpgradeRunStart()

    /** The request cannot mean an upgrade; [reason] is one sentence. */
    public data class Invalid(val reason: String) : UpgradeRunStart()

    /** The launcher could not start the run; [reason] says why. */
    public data class NotStarted(val reason: String) : UpgradeRunStart()
}

/** Starts `splice upgrade` with [args] out of process, its files in [dir]; null once it started, else why not. */
public fun interface UpgradeRunLauncher {
    public fun launch(dir: Path, args: List<String>): String?
}

public class UpgradeRuns(env: EnvReader, private val launcher: UpgradeRunLauncher) {
    private val layout = UpgradeLayout(env)
    private val root: Path = layout.share.resolve("upgrade-runs")
    private val json = Json { ignoreUnknownKeys = true }

    // One start at a time, without holding a monitor across the run's files and the launch: a second
    // request while one starts is Busy, as one while a run goes is.
    private val starting = AtomicBoolean(false)

    // A run's id leads with its start in zero-padded millis, so the newest sorts last by name; two starts
    // in one millisecond still get distinct, ordered stamps. Written only while [starting] is held.
    private var lastStamp = 0L

    public fun start(request: UpgradeRequest): UpgradeRunStart {
        invalid(request)?.let { return UpgradeRunStart.Invalid(it) }
        if (!starting.compareAndSet(false, true)) return UpgradeRunStart.Busy
        return try {
            begin(request)
        } finally {
            starting.set(false)
        }
    }

    private fun begin(request: UpgradeRequest): UpgradeRunStart =
        if (latest()?.state == UpgradeRunState.RUNNING) UpgradeRunStart.Busy else launch(request)

    /** The run's directory and record, then the launch; a launch that fails leaves no run behind. */
    private fun launch(request: UpgradeRequest): UpgradeRunStart {
        val started = maxOf(System.currentTimeMillis(), lastStamp + 1).also { lastStamp = it }
        val id = "%013d-%s".format(started, UUID.randomUUID().toString().take(ID_SUFFIX))
        val dir = Files.createDirectories(root.resolve(id))
        val args = listOf("upgrade") + when {
            request.rollback -> listOf("--rollback")
            request.to != null -> listOf("--to", request.to)
            else -> emptyList()
        }
        Files.writeString(dir.resolve(RUN_FILE), record(id, args, started).toString())
        val refused = launcher.launch(dir, args)
        if (refused != null) {
            layout.discard(dir)
            return UpgradeRunStart.NotStarted(refused)
        }
        prune()
        return UpgradeRunStart.Started(view(dir))
    }

    /** The newest run, or null when the console never started one. */
    public fun latest(): UpgradeRunView? {
        if (!Files.isDirectory(root)) return null
        val newest = Files.list(root).use { dirs -> dirs.filter { Files.isRegularFile(it.resolve(RUN_FILE)) }.toList() }
            .maxByOrNull { it.fileName.toString() } ?: return null
        return view(newest)
    }

    /** A version is one normalized SemVer segment, `v` optional (UpgradeLayout.versionDir's own check,
     *  so what the console asks for is what the CLI would accept); a rollback names none. */
    private fun invalid(request: UpgradeRequest): String? {
        val to = request.to
        return when {
            request.rollback && to != null -> "A rollback returns to the previous release, so it takes no version."
            to != null && !layout.isVersion(to.removePrefix("v")) -> "The version must be a release number like v0.4.1."
            else -> null
        }
    }

    private fun view(dir: Path): UpgradeRunView {
        val run = json.parseToJsonElement(Files.readString(dir.resolve(RUN_FILE))).jsonObject
        val started = JsonScalars.long(run, "started_at_epoch_millis") ?: 0L
        val state = state(dir, started)
        val args = (run["args"] as? JsonArray).orEmpty().mapNotNull(JsonScalars::str)
        return UpgradeRunView(dir.fileName.toString(), args, started, state, exitCode(dir), output(dir))
    }

    private fun state(dir: Path, started: Long): UpgradeRunState {
        finished(dir)?.let { return it }
        if (alive(dir, started)) return UpgradeRunState.RUNNING
        // The shell writes its exit before it exits: read again, or a run that ended between the two
        // reads would be reported lost.
        return finished(dir) ?: UpgradeRunState.LOST
    }

    private fun finished(dir: Path): UpgradeRunState? =
        exitCode(dir)?.let { if (it == 0) UpgradeRunState.SUCCEEDED else UpgradeRunState.FAILED }

    /** The shell's pid is alive, or it has not written one yet and the run is still young. */
    private fun alive(dir: Path, started: Long): Boolean {
        val pid = read(dir.resolve(RUN_PID))?.toLongOrNull()
            ?: return System.currentTimeMillis() - started < START_GRACE_MS
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    private fun exitCode(dir: Path): Int? = read(dir.resolve(RUN_EXIT))?.toIntOrNull()

    private fun output(dir: Path): List<String> {
        val file = dir.resolve(RUN_OUTPUT)
        if (!Files.isRegularFile(file)) return emptyList()
        return Files.readAllLines(file).takeLast(OUTPUT_LINES).map { it.replace(ANSI, "") }
    }

    /** The file's trimmed text, or null while the run's shell has not written it (or wrote nothing yet). */
    private fun read(file: Path): String? =
        if (Files.isRegularFile(file)) Files.readString(file).trim().takeIf { it.isNotEmpty() } else null

    private fun record(id: String, args: List<String>, started: Long): JsonObject = buildJsonObject {
        put("id", id)
        putJsonArray("args") { args.forEach { add(JsonPrimitive(it)) } }
        put("started_at_epoch_millis", started)
    }

    /** The newest [KEPT_RUNS] stay; a run still going is never removed. */
    private fun prune() {
        val runs = Files.list(root).use { dirs -> dirs.filter(Files::isDirectory).toList() }
            .sortedByDescending { it.fileName.toString() }
        runs.drop(KEPT_RUNS).filter { view(it).state != UpgradeRunState.RUNNING }.forEach(layout::discard)
    }
}

// why: eight hex digits of a random UUID keep two ids apart where the millisecond stamp alone could not.
private const val ID_SUFFIX = 8
