// NEW: v0.4.0 FEATURES.md §8 — how a hosted MCP child is spawned, and the host's own failure
// type. Split from HostedServer.kt (concentration, 2026-09-13).
package splice.control.mcp

import splice.core.launch.McpServerSpec
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import java.nio.file.Paths

/** Spawns the child; a seam so tests can run a scripted server and the host never hard-codes Java's launcher. */
public fun interface McpProcessLauncher {
    public operator fun invoke(spec: McpServerSpec): Process
}

/** The default launcher: the spec's command/args/env, stderr discarded (MCP servers log there freely).
 *  A hosted child has no client whose cwd it could inherit, and the daemon's own cwd is whatever
 *  started it, so the child runs in [workingDir] (the home directory): a stated place, not an
 *  accident of the launch. A server that reads its cwd without naming it belongs in
 *  mcp_hosting_exclude (review 2026-09-14). */
public class StdioProcessLauncher(
    private val workingDir: Path = Paths.get(System.getProperty("user.home")),
) : McpProcessLauncher {
    override fun invoke(spec: McpServerSpec): Process {
        val builder = ProcessBuilder(listOf(spec.command) + spec.args).directory(workingDir.toFile())
        builder.environment().putAll(spec.env)
        builder.redirectError(Redirect.DISCARD)
        return builder.start()
    }
}

public class McpHostException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
