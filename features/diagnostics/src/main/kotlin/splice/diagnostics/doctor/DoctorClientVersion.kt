// NEW: v0.4.0 FEATURES.md §2 — the daemon's Claude Code version-drift warning as one doctor row.
// MCP hub step 6: and one row for each installed Claude Code version on which splice's own check found that
// `--plugin-dir-no-mcp` does not hold, so its launches keep each plugin's own MCP servers (PluginFlagVerdicts).
package splice.diagnostics.doctor

import splice.client.mcp.ClaudeCodeVersionsDir
import splice.client.mcp.PluginFlagVerdicts
import splice.core.config.StatePaths
import splice.core.config.UserHome

/** Projects the daemon's aggregate Claude Code drift warning into one doctor row. */
internal class DoctorClientVersion {
    internal fun check(health: HealthView?): DoctorCheck? = health?.clientVersionWarning?.let { warning ->
        DoctorCheck("Claude Code", CheckStatus.WARN, warning)
    }

    /** One row per installed version whose plugin flag check failed, read from the verdict file. */
    internal fun pluginFlag(statePaths: StatePaths): List<DoctorCheck> =
        PluginFlagVerdicts(statePaths.stateDir, ClaudeCodeVersionsDir(UserHome.dir()).path())
            .installedFailures()
            .map { (version, verdict) ->
                DoctorCheck(
                    "Claude Code $version",
                    CheckStatus.INFO,
                    "plugins keep their own MCP servers: --plugin-dir-no-mcp does not hold (${verdict.why})",
                )
            }
}
