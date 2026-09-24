// NEW: `splice restart` — stop the running daemon (stale or current) and cold-start it from THIS
// shell. The daemon reads api-key env vars from its own environment, so a key exported after the
// daemon booted is invisible until a restart — this verb is the documented fix for that trap
// (doctor and the launch warning both point here). In features/lifecycle since LAYOUT-01, beside the
// daemon's own restart route; every line goes through TerminalOutput and the jar through RunningJar.
package splice.lifecycle.restart

import splice.core.GATEWAY_VERSION
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.daemonclient.DaemonProbe
import splice.daemonclient.DaemonSettings
import splice.daemonclient.MgmtKeyFile
import splice.daemonclient.MgmtKeyRead
import splice.lifecycle.start.DaemonColdStart
import splice.topology.TopologyLoader
import splice.topology.TopologyStatePaths

/** The `restart` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions). Every member keeps the old function's name. */
public class RestartCommand(
    private val output: TerminalOutput,
    errors: TerminalOutput,
    private val env: EnvReader,
    jar: RunningJar,
) {

    // The escalation ladder is a process lifecycle, not a control-plane request — it lives on
    // DaemonStop (the symmetric counterpart of DaemonLaunch), which this verb drives.
    private val daemonStop = DaemonStop(output, errors)
    private val coldStart = DaemonColdStart(output, errors, env, jar)

    /** The control port by the daemon's own TOML < state < env precedence; its corrupt-TOML
     *  diagnostic goes to [errors], because stdout belongs to the verb. */
    private val settings = DaemonSettings(errors)

    /** [expectedVersion] is what the restarted daemon must report: this CLI's own, or the release an
     *  upgrade just activated (the old CLI running `splice upgrade` is not the version coming up). */
    public fun restart(expectedVersion: String = GATEWAY_VERSION): Boolean {
        // Load topology once: controlPort AND the FALLBACK head ports come from it. The head ports feed
        // the stop check so a restart never declares success while a head port is still bound (F3).
        // Silence here re-opened F3: a null topology made headPorts empty, `none {}` went vacuously
        // true, and the stop check silently degraded to control-port-only — the exact defect this
        // range closed. Say it out loud, and name the failure; the live enumeration below usually
        // covers for it anyway.
        val topology = Cancellables
            .runCatchingCancellable { TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(env)) }
            .onFailure { failure ->
                output.line(
                    "splice: could not read ${TopologyLoader.configPath(env)} " +
                        "(${SafeFailureText.render(failure)}) — " +
                        "falling back to the running daemon for head ports",
                )
            }
            .getOrNull()
        val port = settings.controlPort(topology, env)
        if (!stopIfRunning(port, topology?.heads?.values?.map { it.port } ?: emptyList())) return false
        val started = coldStart.ensureDaemon(port, expectedVersion)
        if (started) output.line("splice: daemon restarted")
        return started
    }

    // The env is the constructor's (the splitBrainChecks / DaemonSettings idiom) so the stop decision
    // and its message are drivable against a temp CLAUDEX_STATE_DIR — DR-174's arms drive THIS
    // function, not the helper under it.
    internal fun stopIfRunning(port: Int, tomlPorts: List<Int>): Boolean {
        val running = DaemonProbe.healthVersion(port) ?: return true
        val key = stopKeyOrExplain() ?: return false
        val scope = stopScope(DaemonProbe.headPorts(port, key), tomlPorts)
        if (scope.degraded) {
            output.line(
                "splice: WARNING — could not enumerate this daemon's head ports (config unreadable and " +
                    "/api/heads unreachable). The stop check can only see :$port, so a head still " +
                    "holding its port may go unnoticed and the new daemon can hit EADDRINUSE.",
            )
        }
        output.line("splice: stopping daemon $running on :$port…")
        return daemonStop.stopDaemon(port, key, scope.ports).also { stopped ->
            if (!stopped) output.line("splice: the daemon did not stop — terminate it manually and retry")
        }
    }

    /** DR-174: the stop key, or null having SAID which of the two states it is.
     *
     *  This printed "mgmt-key not found at <path>" for a key sitting at 0000 as well as for one
     *  never minted, because AdminSupport.mgmtKey collapsed both into null. The remedies are
     *  opposites — one chmod versus a re-mint the operator cannot even perform while the daemon
     *  holds the old key in memory — so an operator following the message on the unreadable path
     *  was sent to fix the wrong thing, on a verb whose whole job is to stop a running daemon. */
    private fun stopKeyOrExplain(): String? {
        val keyFile = TopologyStatePaths(env).current().mgmtKeyFile
        return when (val read = MgmtKeyFile().read(env)) {
            is MgmtKeyRead.Present -> read.key
            is MgmtKeyRead.Unreadable -> null.also {
                output.line(
                    "splice: mgmt-key at $keyFile is unreadable (${read.reason}) — can't stop the " +
                        "daemon. Fix the file's permissions; it may exist, so nothing needs re-minting.",
                )
            }
            is MgmtKeyRead.Absent -> null.also {
                output.line("splice: mgmt-key not found at $keyFile — can't stop the daemon")
            }
        }
    }

    internal fun stopScope(livePorts: List<Int>?, tomlPorts: List<Int>): StopScope {
        val ports = (livePorts.orEmpty() + tomlPorts).distinct()
        return StopScope(ports, degraded = ports.isEmpty())
    }
}

/** Which ports a stop must see FREED, and whether that list can be trusted.
 *
 *  The union is deliberate: [livePorts] (what the running daemon actually holds) is authoritative,
 *  and [tomlPorts] is kept alongside it so a head the daemon failed to start — and therefore never
 *  lists — is still checked. Extra ports only ever make the stop check stricter. `degraded` is the
 *  honest signal for "both sources failed": an empty list makes `headPorts.none {}` vacuously true,
 *  so the caller must announce the weakened check rather than let it pass as a clean stop. */
internal data class StopScope(val ports: List<Int>, val degraded: Boolean)
