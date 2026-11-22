// NEW: #525 — re-lays splice's own systemd user unit (`splice.service`) from the newly activated jar during
// `splice upgrade` so unit updates (such as `ExecReload`) take effect without requiring a separate `splice install`.
package splice.lifecycle.units

import splice.core.config.InstallPaths
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.lifecycle.hub.HubUnits
import java.nio.file.Path

/** The systemd user units `splice upgrade` updates: the MCP hub's (when installed) and the daemon's (on Linux). */
public data class UpgradeUnits(
    val hub: HubUnits? = null,
    val daemon: SpliceUnits? = null,
)

/** Refreshes `splice.service` from the activated release jar when running on a systemd Linux host. */
internal class UpgradeDaemonUnit(
    private val env: EnvReader,
    private val output: TerminalOutput,
    private val units: SpliceUnits?,
) {
    /** Reads `splice.service` from [liveJar], renders it for this install's JVM and `splice` link, and lays it
     *  through [SpliceUnits.lay] (idempotent, never overwrites user overrides in `~/.config/systemd/user`). */
    fun refresh(liveJar: Path, javaHome: Path = Path.of(System.getProperty("java.home"))) {
        val folder = units ?: return
        val unit = DaemonUnit(javaHome, InstallPaths(envReader = env).binDir.resolve("splice"))
        val bytes = unit.bytesFromJar(liveJar)
        if (bytes == null) {
            unit.refusal()?.let(output::line)
            return
        }
        folder.lay(mapOf(DAEMON_UNIT_NAME to bytes))
    }
}
