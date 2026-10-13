// NEW: v0.4.0 FEATURES.md §2 — the daemon's Claude Code version-drift warning as one doctor row.
// The MCP hub row too: whether the daemon runs the hub, as its own boot check (McpHubGate) decided.
// MCP hub step 6: and one row for each installed Claude Code version on which splice's own check found that
// `--plugin-dir-no-mcp` does not hold, so its launches keep each plugin's own MCP servers (PluginFlagVerdicts).
package splice.diagnostics.doctor

import splice.client.mcp.ClaudeCodeVersionsDir
import splice.client.mcp.PluginFlagVerdicts
import splice.core.config.StatePaths
import splice.core.config.UserHome

/** Projects the daemon's aggregate Claude Code drift warning into one doctor row. */
internal class DoctorClientVersion {
    internal fun check(health: HealthView?): DoctorCheck? = health?.client?.versionWarning?.let { warning ->
        DoctorCheck("Claude Code", CheckStatus.WARN, warning)
    }

    /** MCP hub step 6: whether the running daemon runs the hub, as its own boot check decided and /health says. Off is
     *  a note, never a finding: MCP servers are then hosted in the daemon, the supported fallback. No row when the
     *  daemon is stopped or does not say. */
    internal fun mcpHub(health: HealthView?): DoctorCheck? = health?.client?.mcpHub?.let { hub ->
        if (hub.on) {
            DoctorCheck("MCP hub", CheckStatus.OK, "on: each session's MCP servers run under the hub")
        } else {
            val why = "the MCP hub is off on this machine" + (hub.offReason?.let { ": $it" } ?: "")
            DoctorCheck("MCP hub", CheckStatus.INFO, "$why. MCP servers are hosted in the daemon instead")
        }
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
