// NEW: v0.4.0 FEATURES.md §8 — how a hosted MCP child is spawned, and the host's own failure
// type. Split from HostedServer.kt (concentration, 2026-09-13).
package splice.control.mcp

import splice.core.launch.McpServerSpec
import java.lang.ProcessBuilder.Redirect

/** Spawns the child; a seam so tests can run a scripted server and the host never hard-codes Java's launcher. */
public fun interface McpProcessLauncher {
    public operator fun invoke(spec: McpServerSpec): Process
}

/** The default launcher: the spec's command/args/env, stderr discarded (MCP servers log there freely). */
public class StdioProcessLauncher : McpProcessLauncher {
    override fun invoke(spec: McpServerSpec): Process {
        val builder = ProcessBuilder(listOf(spec.command) + spec.args)
        builder.environment().putAll(spec.env)
        builder.redirectError(Redirect.DISCARD)
        return builder.start()
    }
}

public class McpHostException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
