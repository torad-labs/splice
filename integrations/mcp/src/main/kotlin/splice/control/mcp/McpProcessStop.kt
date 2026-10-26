// NEW: v0.4.0 FEATURES.md §8 — all signalled MCP children share one shutdown grace budget.
package splice.control.mcp

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// why: every owned generation shares this bounded grace before forced teardown starts.
private const val DESTROY_GRACE_MS = 2_000L

// why: scope discovery must not hold shutdown while systemd has not yet published a generated unit.
private const val CONTAINMENT_LOOKUP_MS = 1_000L

/** Keeps a known uncontained launch distinct from a scope that could not be read. */
internal sealed class McpProcessContainmentState {
    data class Present(val containment: McpProcessContainment) : McpProcessContainmentState()

    object Absent : McpProcessContainmentState()

    /** systemd no longer knows the scope and its root has exited, so nothing in it can still run. */
    object Removed : McpProcessContainmentState()

    object Unknown : McpProcessContainmentState()
}

/** Resolves launch ownership again while systemd registers a generated scope after its root started. */
internal fun interface McpProcessContainmentSource {
    fun resolve(): McpProcessContainmentState
}

/** The child, launch-time root identity and cgroup evidence that keep reparented workers in this generation. */
internal class McpProcessGeneration(
    val process: Process,
    private val containment: McpProcessContainmentSource?,
    private val root: ProcessHandle = process.toHandle(),
) {
    private val evidenceLock = Any()
    private var capturedContainment: McpProcessContainmentState? = null
    private val capturedDescendants = mutableSetOf<ProcessHandle>()

    /** Capture root descendants and positive scope evidence before either can disappear after launch. */
    fun observe() {
        descendants()
        containmentState()
    }

    fun handles(): List<ProcessHandle> {
        val members = when (val state = containmentState()) {
            is McpProcessContainmentState.Present -> state.containment.members().orEmpty()
            McpProcessContainmentState.Absent,
            McpProcessContainmentState.Removed,
            McpProcessContainmentState.Unknown,
            -> emptyList()
        }
        return (descendants() + members + root).distinct()
    }

    /** Only known-empty containment and exited captured descendants prove a dead root's generation settled. */
    fun unresolved(): Boolean {
        val descendants = descendants()
        if (root.isAlive || descendants.any(ProcessHandle::isAlive)) return true
        return when (val state = containmentState()) {
            is McpProcessContainmentState.Present -> state.containment.members()?.any(ProcessHandle::isAlive) ?: true
            McpProcessContainmentState.Absent,
            McpProcessContainmentState.Removed,
            -> false
            McpProcessContainmentState.Unknown -> true
        }
    }

    private fun descendants(): List<ProcessHandle> = synchronized(evidenceLock) {
        capturedDescendants += root.descendants().toList()
        capturedDescendants.toList()
    }

    private fun containmentState(): McpProcessContainmentState = synchronized(evidenceLock) {
        capturedContainment ?: resolveContainment().also { state ->
            if (state !== McpProcessContainmentState.Unknown) capturedContainment = state
        }
    }

    private fun resolveContainment(): McpProcessContainmentState =
        containment?.resolve() ?: McpProcessContainmentState.Absent
}

/** A unique cgroup is the process-generation identity; a failed read never proves its members are gone. */
internal class McpProcessContainment(private val directory: Path) {
    fun members(): List<ProcessHandle>? = try {
        val ids = Files.readAllLines(directory.resolve("cgroup.procs")).map { it.toLongOrNull() }
        if (ids.any { it == null }) {
            null
        } else {
            ids.mapNotNull { it?.let(ProcessHandle::of)?.orElse(null) }
        }
    } catch (_: NoSuchFileException) {
        emptyList()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

private data class McpStopWait(
    val exited: Boolean,
    val interrupted: Boolean,
)

/** `systemctl --user show <unit> -p ControlGroup --value`: the scope's cgroup, blank when systemd knows no such
 *  unit, null when systemd could not be asked. A seam, because when systemd registers or removes a scope is not a
 *  test's to order. */
internal fun interface McpScopeLookup {
    fun controlGroup(unit: String): String?
}

/** Asks the user's systemd manager, within [CONTAINMENT_LOOKUP_MS]. */
internal class SystemdScopeLookup : McpScopeLookup {
    override fun controlGroup(unit: String): String? = try {
        val lookup = ProcessBuilder(
            "systemctl",
            "--user",
            "show",
            unit,
            "-p",
            "ControlGroup",
            "--value",
        ).redirectErrorStream(true).start()
        if (!lookup.waitFor(CONTAINMENT_LOOKUP_MS, TimeUnit.MILLISECONDS)) {
            lookup.destroyForcibly()
            null
        } else {
            lookup.inputStream.bufferedReader().use { it.readText() }.trim().takeIf { lookup.exitValue() == 0 }
        }
    } catch (_: IOException) {
        null
    }
}

internal class McpProcessStop(private val scopes: McpScopeLookup = SystemdScopeLookup()) {
    private val ownershipLock = Any()
    private val unresolved = mutableSetOf<McpProcessGeneration>()

    /** Carries launch-time descendants and a generated scope before either can disappear. */
    fun capture(process: Process, unit: String? = null): McpProcessGeneration {
        val root = process.toHandle()
        return McpProcessGeneration(
            process,
            McpProcessContainmentSource { containment(root, unit) },
            root,
        ).also(McpProcessGeneration::observe)
    }

    /** Stops a fresh list only where no earlier generation capture exists. */
    fun stop(processes: List<Process>) {
        stopGenerations(processes.map(::capture))
    }

    /** Stops launch-captured generations and retains every one whose exact containment cannot yet prove exit. */
    fun stopGenerations(
        generations: List<McpProcessGeneration>,
    ): List<McpProcessGeneration> = synchronized(ownershipLock) {
        val owned = (unresolved + generations).distinct()
        val graceful = owned.flatMap(McpProcessGeneration::handles).distinct()
        graceful.asReversed().forEach { it.destroy() }
        val first = waitFor(
            graceful,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DESTROY_GRACE_MS),
        )
        val forced = (graceful + owned.flatMap(McpProcessGeneration::handles))
            .distinct()
            .filter(ProcessHandle::isAlive)
        forced.asReversed().forEach { it.destroyForcibly() }
        val second = waitFor(
            forced,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DESTROY_GRACE_MS),
        )
        if ((first + second).any(McpStopWait::interrupted)) Thread.currentThread().interrupt()
        unresolved.clear()
        unresolved += owned.filter(McpProcessGeneration::unresolved)
        unresolved.toList()
    }

    /** Whether this stop owner still cannot prove [generation] and its owned members exited. */
    fun retains(generation: McpProcessGeneration): Boolean = synchronized(ownershipLock) {
        unresolved.any { it === generation }
    }

    /** Callers that already signalled every child wait one shared grace. */
    fun await(processes: List<Process>) {
        val generations = processes.map(::capture)
        val waits = waitFor(
            generations.flatMap(McpProcessGeneration::handles).distinct(),
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DESTROY_GRACE_MS),
        )
        if (waits.any(McpStopWait::interrupted)) Thread.currentThread().interrupt()
    }

    private fun containment(process: ProcessHandle, unit: String?): McpProcessContainmentState {
        if (unit == null) return McpProcessContainmentState.Absent
        // A launch-created scope names the cgroup even when its root already reparented a stdin-owning worker.
        val answer = scopes.controlGroup(unit)
        val child = answer?.takeIf(String::isNotBlank)
            ?: cgroup(process.pid())?.takeIf { it.substringAfterLast('/') == unit }
        return when {
            child != null -> McpProcessContainmentState.Present(
                McpProcessContainment(Path.of("/sys/fs/cgroup").resolve(child.removePrefix("/"))),
            )
            // systemd answered that it knows no such scope. While the root lives, that is a scope not registered
            // yet. Once the root has exited nothing can register it, and a scope systemd no longer knows holds no
            // process.
            answer != null && !process.isAlive -> McpProcessContainmentState.Removed
            else -> McpProcessContainmentState.Unknown
        }
    }

    private fun cgroup(pid: Long): String? = try {
        Files.readAllLines(Path.of("/proc", pid.toString(), "cgroup"))
            .firstOrNull { it.startsWith("0::") }
            ?.substringAfter("0::")
    } catch (_: IOException) {
        null
    }

    private fun waitFor(handles: List<ProcessHandle>, deadline: Long): List<McpStopWait> =
        handles.map { handle -> waitFor(handle, deadline) }

    private fun waitFor(process: ProcessHandle, deadline: Long): McpStopWait = try {
        val left = (deadline - System.nanoTime()).coerceAtLeast(0L)
        process.onExit().get(left, TimeUnit.NANOSECONDS)
        McpStopWait(exited = true, interrupted = false)
    } catch (_: InterruptedException) {
        McpStopWait(exited = false, interrupted = true)
    } catch (_: TimeoutException) {
        McpStopWait(exited = false, interrupted = false)
    }
}
