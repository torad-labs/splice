// NEW: the --plugin-dir-no-mcp verdicts and their check, moved out of ControlPlane.kt by their own seam, behavior
// unchanged (concentration, Oct 13, splice-lead). MCP hub step 6: each installed Claude Code version's verdict, and the
// background check that records it. Daemon asks for a pass at start; a launch on a version with no verdict asks again.
package splice.app

import splice.client.mcp.ClaudeCodeVersionsDir
import splice.client.mcp.PluginFlagChecks
import splice.client.mcp.PluginFlagHolds
import splice.client.mcp.PluginFlagVerdicts
import splice.core.config.UserHome
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers

internal class ControlPluginFlags(
    /** The daemon's shared environment: its state, its settings, its management key and its log sink. */
    daemon: DaemonEnvironment,
    probeScope: LifecycleScope,
) {
    private val verdicts = PluginFlagVerdicts(daemon.statePaths.stateDir, ClaudeCodeVersionsDir(UserHome.dir()).path())
    val checks = PluginFlagChecks(verdicts, daemon.log, probeScope, ProcessDispatchers().io())

    /** A version with no verdict yet launches without the flag and asks for its check, which runs apart. */
    val holds = PluginFlagHolds { version ->
        verdicts.holds(version).also { held ->
            if (!held && version !in verdicts.all()) checks.request()
        }
    }
}
