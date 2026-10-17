// NEW: v0.4.0 FEATURES.md §2 — the daemon's Claude Code version-drift warning as one doctor row.
// The MCP hub row too: whether the daemon runs the hub, as its own boot check (McpHubGate) decided.
// MCP hub step 6: and one row for each installed Claude Code version on which splice's own check found that
// `--plugin-dir-no-mcp` does not hold, so its launches keep each plugin's own MCP servers (PluginFlagVerdicts).
package splice.diagnostics.doctor

import splice.client.mcp.ClaudeCodeVersionsDir
import splice.client.mcp.HUB_FRONT_CONNECT_MS
import splice.client.mcp.PluginFlagVerdicts
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.daemonclient.DaemonProbe
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files

// why: the loopback port splice-mcp-front.socket listens on (the hub's unit), which every launch checks the same way.
private const val HUB_FRONT_PORT = 3095

private const val HUB_ROW = "MCP hub"

/** Projects the daemon's aggregate Claude Code drift warning into one doctor row. */
internal class DoctorClientVersion {
    internal fun check(health: HealthView?): DoctorCheck? = health?.client?.versionWarning?.let { warning ->
        DoctorCheck("Claude Code", CheckStatus.WARN, warning)
    }

    /** MCP hub step 6: whether the running daemon runs the hub, as its own boot check decided and /health says, and
     *  whether launches can use it: by the launch's own rule, only once a front has run here (its front.json) and while
     *  one answers on its port. Off is a note, never a finding: MCP servers are then hosted in the daemon, the
     *  supported fallback. No row when the daemon is stopped or does not say. */
    internal fun mcpHub(health: HealthView?, statePaths: StatePaths): DoctorCheck? =
        health?.client?.mcpHub?.let { hub -> hubRow(hub, statePaths) }

    private fun hubRow(hub: DaemonProbe.HealthMcpHub, statePaths: StatePaths): DoctorCheck =
        if (hub.on) {
            front(statePaths)
        } else {
            val why = "the MCP hub is off on this machine" + (hub.offReason?.let { ": $it" } ?: "")
            DoctorCheck(HUB_ROW, CheckStatus.INFO, "$why. MCP servers are hosted in the daemon instead")
        }

    private fun front(statePaths: StatePaths): DoctorCheck = when {
        !Files.exists(statePaths.stateDir.resolve("mcp-hub").resolve("front.json")) -> DoctorCheck(
            HUB_ROW,
            CheckStatus.INFO,
            "installed, but its front has never run here, so each session starts its own MCP servers",
            "systemctl --user enable --now splice-mcp-front.socket",
            fixKind = FixKind.COMMAND,
        )
        !frontAnswers() -> DoctorCheck(
            HUB_ROW,
            CheckStatus.WARN,
            "its front is not answering on 127.0.0.1:$HUB_FRONT_PORT, so each new session starts its own MCP servers",
            "systemctl --user status splice-mcp-front.socket splice-mcp-front.service",
            fixKind = FixKind.COMMAND,
        )
        else -> DoctorCheck(HUB_ROW, CheckStatus.OK, "on: each session's MCP servers run under the hub")
    }

    private fun frontAnswers(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", HUB_FRONT_PORT), HUB_FRONT_CONNECT_MS) }
        true
    } catch (_: IOException) {
        false
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
