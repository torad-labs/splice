// NEW: v0.4.0 FEATURES.md §8 — all signalled MCP children share one shutdown grace budget.
package splice.control.mcp

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val DESTROY_GRACE_MS = 2_000L
private const val CONTAINMENT_LOOKUP_MS = 1_000L

/** Resolves launch ownership again while systemd registers a generated scope after its root started. */
internal fun interface McpProcessContainmentSource {
    fun resolve(): McpProcessContainment?
}

/** The child and its cgroup at launch, so reparented workers stay owned by this exact generation. */
internal class McpProcessGeneration(
    val process: Process,
    private val containment: McpProcessContainmentSource?,
) {
    fun handles(): List<ProcessHandle> {
        val root = process.toHandle()
        return (root.descendants().toList() + containment?.resolve()?.members().orEmpty() + root).distinct()
    }

    /** A scope proves every member stopped. Without one, only the root's exit permits recovery. */
    fun unresolved(): Boolean {
        val members = containment?.resolve()?.members()
        return members?.any(ProcessHandle::isAlive) ?: process.isAlive
    }
}

/** A unique cgroup is the process-generation identity; a failed read never proves its members are gone. */
internal class McpProcessContainment(private val directory: Path) {
    fun members(): List<ProcessHandle>? = try {
        Files.readAllLines(directory.resolve("cgroup.procs"))
            .mapNotNull { it.toLongOrNull()?.let(ProcessHandle::of)?.orElse(null) }
    } catch (_: NoSuchFileException) {
        emptyList()
    } catch (_: IOException) {
        null
    }
}

private data class McpStopWait(
    val exited: Boolean,
    val interrupted: Boolean,
)

internal class McpProcessStop {
    private val ownershipLock = Any()
    private val unresolved = mutableSetOf<McpProcessGeneration>()

    /** Carries a generated unit until systemd exposes its ControlGroup, rather than losing ownership on one early read. */
    fun capture(process: Process, unit: String? = null): McpProcessGeneration =
        McpProcessGeneration(process, McpProcessContainmentSource { containment(process.toHandle(), unit) })

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

    private fun containment(process: ProcessHandle, unit: String?): McpProcessContainment? {
        val child = unit?.let(::scope)?.takeIf(String::isNotBlank) ?: cgroup(process.pid()) ?: return null
        if (child == cgroup(ProcessHandle.current().pid())) return null
        return McpProcessContainment(Path.of("/sys/fs/cgroup").resolve(child.removePrefix("/")))
    }

    /** A launch-created scope names the cgroup even when its root already reparented a stdin-owning worker. */
    private fun scope(unit: String): String? = try {
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
