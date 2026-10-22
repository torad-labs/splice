// NEW: v0.4.0 FEATURES.md §8 — how a hosted MCP child is spawned, and the host's own failure
// type. Split from HostedServer.kt (concentration, 2026-09-13).
package splice.control.mcp

import splice.client.mcp.McpServerProvenance
import splice.client.mcp.McpServerSpec
import splice.core.config.UserHome
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Spawns the child; a seam so tests can run a scripted server and the host never hard-codes Java's launcher. */
public fun interface McpProcessLauncher {
    public operator fun invoke(spec: McpServerSpec): Process
}

/** A launcher with an OS identity captured before the root process can reparent its stdin-owning worker. */
internal fun interface McpProcessGenerationSource {
    fun capture(process: Process): McpProcessGeneration
}

/** The default launcher: the spec's command/args/env, stderr drained separately with content withheld.
 *  A hosted child runs in [workingDir] (the home directory) unless an explicitly registered service
 *  supplied its validated fixed cwd. A client cwd stays per-session and never reaches this shared launcher.
 *
 *  V4-147: [containment] decides the two things a spawn owes the box — the cgroup the child runs in
 *  (a slice the host caps) and its oom_score_adj (off splice's inherited -1000). Null keeps the
 *  plain spawn, which is what a test wants and what a box with no such slice gets anyway. */
internal class StdioProcessLauncher(
    private val workingDir: Path = UserHome.dir(),
    private val containment: McpContainment? = null,
) : McpProcessLauncher, McpProcessGenerationSource {
    private val stops = McpProcessStop()
    private val generations = ConcurrentHashMap<Process, McpProcessGeneration>()

    override fun invoke(spec: McpServerSpec): Process {
        val command = listOf(spec.command) + spec.args
        val placement = containment?.prepare(command)
        val folder = spec.cwd?.takeIf { spec.provenance == McpServerProvenance.REGISTERED_SERVICE }
            ?.let(Path::of) ?: workingDir
        val builder = ProcessBuilder(placement?.command ?: command).directory(folder.toFile())
        builder.environment().putAll(spec.env)
        val child = builder.start()
        generations[child] = stops.capture(child, placement?.unit)
        // AFTER start and never before: the adj is written to the CHILD's pid, which is the one thing
        // splice knows here and no process-tree match could tell it safely.
        containment?.protect(spec.name, child)
        return child
    }

    override fun capture(process: Process): McpProcessGeneration =
        generations.remove(process) ?: stops.capture(process)
}

/** Stderr is untrusted and may contain credentials. Drain it without buffering lines or logging bytes;
 *  one authored signal per child distinguishes diagnostic output from complete silence. */
internal class McpStderr(private val log: LogSink) {
    fun watch(name: String, process: Process) {
        Executors.defaultThreadFactory().newThread {
            var reported = false
            val bytes = ByteArray(STDERR_BUFFER)
            try {
                process.errorStream.use { stream ->
                    while (stream.read(bytes) != -1) {
                        if (!reported) {
                            log("[mcp-host] $name: child stderr emitted (content withheld)\n")
                            reported = true
                        }
                    }
                }
            } catch (_: IOException) {
                if (process.isAlive) log("[mcp-host] $name: child stderr could not be read\n")
            }
        }.apply {
            isDaemon = true
            this.name = "mcp-host-stderr"
        }.start()
    }
}

/** What a host operation answers: the value, or the words the client is told. A refusal is an answer, never an exception; callers
 *  match both arms. */
internal sealed class McpResult<out T> {
    data class Served<out T>(val value: T) : McpResult<T>()

    data class Refused(val reason: String, val timedOut: Boolean = false) : McpResult<Nothing>()
}

private const val STDERR_BUFFER = 512
