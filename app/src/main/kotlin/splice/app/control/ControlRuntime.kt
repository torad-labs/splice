// NEW: the runtime collaborators a ControlServer hands to its mounts, grouped out of its constructor so the
// constructor stays under the strict LongParameterList ceiling (2026-10-07). Each field keeps the default
// it had as a ControlServer parameter.
package splice.app.control

import kotlinx.coroutines.CoroutineDispatcher
import splice.control.mcp.McpHost
import splice.control.mcp.McpReply
import splice.core.version.ClientVersionTracker
import splice.launch.recipe.LaunchService
import splice.lifecycle.restart.ShutdownDaemon
import splice.sessions.registry.SessionSource
import splice.upstream.codemode.ProcessDispatchers

public data class ControlRuntime(
    val launchService: LaunchService? = null,
    val shutdownDaemon: ShutdownDaemon = ShutdownDaemon {},
    val sessions: SessionSource? = null,
    /** v0.4.0 shared MCP hosting; null keeps the control plane exactly as before. */
    val mcpHost: McpHost? = null,
    val clientVersions: ClientVersionTracker = ClientVersionTracker(),
    val mcpServices: McpServiceRefresh? = null,
    /** Blocking management handlers never occupy Netty's connection-affine application executors. */
    val callDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
)

/** App-owned service installation, separate from the transport host's process and session lifecycle. */
public fun interface McpServiceRefresh {
    public suspend fun refresh(): McpReply
}
