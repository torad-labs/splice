// NEW: v0.4.0 FEATURES.md §8 — how a hosted MCP child is spawned, and the host's own failure
// type. Split from HostedServer.kt (concentration, 2026-09-13).
package splice.control.mcp

import splice.core.launch.McpServerSpec
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors

/** Spawns the child; a seam so tests can run a scripted server and the host never hard-codes Java's launcher. */
public fun interface McpProcessLauncher {
    public operator fun invoke(spec: McpServerSpec): Process
}

/** The default launcher: the spec's command/args/env, stderr drained separately with content withheld.
 *  A hosted child has no client whose cwd it could inherit, and the daemon's own cwd is whatever
 *  started it, so the child runs in [workingDir] (the home directory): a stated place, not an
 *  accident of the launch. A server that reads its cwd without naming it belongs in
 *  mcp_hosting_exclude (review 2026-09-14).
 *
 *  V4-147: [containment] decides the two things a spawn owes the box — the cgroup the child runs in
 *  (a slice the host caps) and its oom_score_adj (off splice's inherited -1000). Null keeps the
 *  plain spawn, which is what a test wants and what a box with no such slice gets anyway. */
public class StdioProcessLauncher(
    private val workingDir: Path = Paths.get(System.getProperty("user.home")),
    private val containment: McpContainment? = null,
) : McpProcessLauncher {
    override fun invoke(spec: McpServerSpec): Process {
        val command = listOf(spec.command) + spec.args
        val builder = ProcessBuilder(containment?.placed(command) ?: command).directory(workingDir.toFile())
        builder.environment().putAll(spec.env)
        val child = builder.start()
        // AFTER start and never before: the adj is written to the CHILD's pid, which is the one thing
        // splice knows here and no process-tree match could tell it safely.
        containment?.protect(spec.name, child)
        return child
    }
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

public class McpHostException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private const val STDERR_BUFFER = 512
