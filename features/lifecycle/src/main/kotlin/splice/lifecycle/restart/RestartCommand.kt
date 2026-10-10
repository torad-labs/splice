// NEW: `splice restart` — stop the running daemon (stale or current) and cold-start it from THIS
// shell. The daemon reads api-key env vars from its own environment, so a key exported after the
// daemon booted is invisible until a restart — this verb is the documented fix for that trap
// (doctor and the launch warning both point here). In features/lifecycle since LAYOUT-01, beside the
// daemon's own restart route; every line goes through TerminalOutput and the jar through RunningJar.
package splice.lifecycle.restart

import splice.core.GATEWAY_VERSION
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.core.util.TopologyRefusal
import splice.daemonclient.DaemonProbe
import splice.daemonclient.DaemonSettings
import splice.daemonclient.MgmtKeyFile
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.Reading
import splice.lifecycle.start.DaemonColdStart
import splice.lifecycle.upgrade.CompactionWait
import splice.lifecycle.upgrade.JdkUpgradeInflight
import splice.topology.TopologyLoader
import splice.topology.TopologyStatePaths
import java.nio.file.Path

/** The fix session a CLI offers for a splice.toml that cannot serve: true when every finding was fixed, so the
 *  caller may read the file again. The default offers nothing, which is what a non-interactive caller wants; :app
 *  wires the real session, which lives there with the terminal. */
public fun interface ConfigRepair {
    public fun offer(): Boolean
}

/** The `restart` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions). Every member keeps the old function's name. */
public class RestartCommand(
    private val output: TerminalOutput,
    errors: TerminalOutput,
    private val env: EnvReader,
    jar: RunningJar,
    private val coldStart: DaemonColdStart = DaemonColdStart(output, errors, env, jar),
    private val repair: ConfigRepair = ConfigRepair { false },
) {

    // The escalation ladder is a process lifecycle, not a control-plane request — it lives on
    // DaemonStop (the symmetric counterpart of DaemonLaunch), which this verb drives.
    private val daemonStop = DaemonStop(output, errors)

    /** The control port by the daemon's own TOML < state < env precedence; its corrupt-TOML
     *  diagnostic goes to [errors], because stdout belongs to the verb. */
    private val settings = DaemonSettings(errors)

    /** [expectedVersion] is what the restarted daemon must report: this CLI's own, or the release an
     *  upgrade just activated (the old CLI running `splice upgrade` is not the version coming up).
     *  [waitForCompactions] false is `--now`, and the upgrade's own call, which has waited already. */
    public fun restart(expectedVersion: String = GATEWAY_VERSION, waitForCompactions: Boolean = true): Boolean {
        // Load topology once: controlPort AND the FALLBACK head ports come from it. The head ports feed
        // the stop check so a restart never declares success while a head port is still bound (F3).
        // Silence here re-opened F3: a null topology made headPorts empty, `none {}` went vacuously
        // true, and the stop check silently degraded to control-port-only — the exact defect this
        // range closed. Say it out loud, and name the failure; the live enumeration below usually
        // covers for it anyway.
        val topology = readTopology() ?: return false
        val port = settings.controlPort(topology.getOrNull(), env)

        // V4-395: before any stop or unit verb. A unit that is another home's is never this verb's to touch.
        val refusal = coldStart.foreignUnitRefusal(port)
        return if (refusal == null) {
            restartOwn(
                topology.getOrNull()?.heads?.values?.map { it.port }.orEmpty(),
                port,
                expectedVersion,
                waitForCompactions,
            )
        } else {
            output.line(refusal)
            false
        }
    }

    /** The topology, as a Result that holds null when the file could not be read but a running daemon can still answer
     *  for the head ports; null when the restart must not proceed at all. A splice.toml this build refuses is the
     *  second case: nothing is stopped, every finding is printed, and on a terminal the fix session is offered and the
     *  file read again, so `splice restart` repairs and proceeds in one go. */
    private fun readTopology(): Result<Topology?>? {
        val path = TopologyLoader.configPath(env)
        val read = Cancellables.runCatchingCancellable { TopologyLoader.loadForBoot(path).topology }
        val failure = read.exceptionOrNull() ?: return read
        output.line("splice: ${SafeFailureText.render(failure)}")
        return if (failure is TopologyRefusal) repaired(path) else withRunningDaemon(path)
    }

    /** A splice.toml this build refuses: nothing is stopped, and on a terminal the fix session is offered and the file
     *  read again, so one `splice restart` repairs and proceeds. Null when the restart must not go on. */
    private fun repaired(path: Path): Result<Topology?>? {
        output.line("splice: nothing was stopped and $path is unchanged")
        if (!repair.offer()) return null
        val again = Cancellables.runCatchingCancellable { TopologyLoader.loadForBoot(path).topology }
            .onFailure { output.line("splice: ${SafeFailureText.render(it)}") }
        return if (again.isFailure) null else again
    }

    /** An unreadable file is not a refusal: a daemon that is up still answers for the head ports. */
    private fun withRunningDaemon(path: Path): Result<Topology?>? {
        if (DaemonProbe.healthVersion(settings.controlPort(null, env)) != null) {
            output.line("splice: falling back to the running daemon for head ports")
            return Result.success(null)
        }
        output.line("splice: cannot start the daemon until $path is fixed")
        return null
    }

    /** The daemon on [port] is this home's, or no unit claims it: restart it through its unit when the
     *  unit runs it, else stop it here (the head ports [tomlPorts] must free too) and cold-start. */
    private fun restartOwn(
        tomlPorts: List<Int>,
        port: Int,
        expectedVersion: String,
        waitForCompactions: Boolean,
    ): Boolean {
        val unit = coldStart.activeUnit()
        if (unit != null) return restartThroughUnit(unit, port, expectedVersion, waitForCompactions)
        return if (stopIfRunning(port, tomlPorts, waitForCompactions)) {
            coldStart.ensureDaemon(port, expectedVersion).also { started ->
                if (started) output.line("splice: daemon restarted")
            }
        } else {
            false
        }
    }

    /** V4-243: the daemon is [unit]'s, so systemd restarts it. Stopping it here and then starting the
     *  unit reached a unit still active in its shutdown tail, a no-op, and the daemon's exit then
     *  waited out the unit's restart backoff (46 s at the sixth restart, 2026-09-25). systemd's stop
     *  drains the in-flight turns the way the shutdown route does, so only the compaction wait stays
     *  here, and it runs only when a daemon answers. */
    private fun restartThroughUnit(
        unit: String,
        port: Int,
        expectedVersion: String,
        waitForCompactions: Boolean,
    ): Boolean {
        if (waitForCompactions && DaemonProbe.healthVersion(port) != null) {
            CompactionWait(output, JdkUpgradeInflight(env, port)).await()
        }
        val restarted = coldStart.restartUnit(unit, port, expectedVersion)
        if (restarted) output.line("splice: daemon restarted")
        return restarted
    }

    // The env is the constructor's (the splitBrainChecks / DaemonSettings idiom) so the stop decision
    // and its message are drivable against a temp CLAUDEX_STATE_DIR — DR-174's arms drive THIS
    // function, not the helper under it.
    internal fun stopIfRunning(port: Int, tomlPorts: List<Int>, waitForCompactions: Boolean = true): Boolean {
        val running = DaemonProbe.healthVersion(port) ?: return true
        val key = stopKeyOrExplain() ?: return false
        if (waitForCompactions) CompactionWait(output, JdkUpgradeInflight(env, port)).await()
        val scope = stopScope(DaemonProbe.headPorts(port, key), tomlPorts)
        scope.unseen?.let { why ->
            output.line(
                "splice: WARNING: could not enumerate this daemon's head ports ($why). The stop check " +
                    "cannot see every head the daemon runs, so a head still holding its port may go " +
                    "unnoticed and the new daemon can hit EADDRINUSE.",
            )
        }
        output.line("splice: stopping daemon $running on :$port…")
        return daemonStop.stopDaemon(port, key, scope.ports).also { stopped ->
            if (!stopped) output.line("splice: the daemon did not stop; terminate it manually and retry")
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
                    "splice: mgmt-key at $keyFile is unreadable (${read.reason}); can't stop the " +
                        "daemon. Fix the file's permissions; it may exist, so nothing needs re-minting.",
                )
            }
            is MgmtKeyRead.Absent -> null.also {
                output.line("splice: mgmt-key not found at $keyFile; can't stop the daemon")
            }
        }
    }

    /** A refused /api/heads leaves the toml's ports to stand alone. A malformed one is a running daemon whose
     *  heads cannot be listed, so the scope stays degraded whatever the toml names. */
    internal fun stopScope(live: Reading<List<Int>>, tomlPorts: List<Int>): StopScope {
        val ports = ((live as? Reading.Answered)?.value.orEmpty() + tomlPorts).distinct()
        val unseen = when {
            live is Reading.Malformed -> "/api/heads answered with a list this CLI cannot read"
            ports.isEmpty() -> "config unreadable and /api/heads unreachable"
            else -> null
        }
        return StopScope(ports, unseen)
    }
}

/** Which ports a stop must see FREED, and whether that list can be trusted.
 *
 *  The union is deliberate: the running daemon's own list is authoritative, and the toml's ports are
 *  kept alongside it so a head the daemon failed to start — and therefore never lists — is still
 *  checked. Extra ports only ever make the stop check stricter. [unseen] names why the list cannot be
 *  trusted: both sources failed, where an empty list makes `headPorts.none {}` vacuously true, or the
 *  daemon answered with a list it could not read. The caller announces the weakened check rather than
 *  let it pass as a clean stop. */
internal data class StopScope(val ports: List<Int>, val unseen: String?) {
    val degraded: Boolean get() = unseen != null
}
