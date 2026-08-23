// NEW: `splice restart` — stop the running daemon (stale or current) and cold-start it from THIS
// shell. The daemon reads api-key env vars from its own environment, so a key exported after the
// daemon booted is invisible until a restart — this verb is the documented fix for that trap
// (doctor and the launch warning both point here). :app is wall-exempt for println.
package splice.app.cli

import splice.app.DaemonProbe
import splice.app.TopologyLoader
import splice.core.config.StatePaths
import splice.core.util.EnvReader

/** The `restart` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions). Every member keeps the old function's name. */
internal class RestartCommand {

    // The escalation ladder is a process lifecycle, not a control-plane request — it lives on
    // DaemonStop (the symmetric counterpart of DaemonLaunch), which this verb drives.
    private val daemonStop = DaemonStop()

    internal fun restart(): Boolean {
        // Load topology once: controlPort AND the FALLBACK head ports come from it. The head ports feed
        // the stop check so a restart never declares success while a head port is still bound (F3).
        val topology = runCatching { TopologyLoader.loadOrMaterialize(TopologyLoader.configPath()) }.getOrNull()
        val port = AdminSupport.controlPort(topology)
        if (topology == null) {
            // Silence here re-opened F3: a null topology made headPorts empty, `none {}` went vacuously
            // true, and the stop check silently degraded to control-port-only — the exact defect this
            // range closed. Say it out loud; the live enumeration below usually covers for it anyway.
            println(
                "splice: could not read ${TopologyLoader.configPath()} — " +
                    "falling back to the running daemon for head ports",
            )
        }
        if (!stopIfRunning(port, topology?.heads?.values?.map { it.port } ?: emptyList())) return false
        val started = AdminSupport.ensureDaemon(port)
        if (started) println("splice: daemon restarted with this shell's environment")
        return started
    }

    private fun stopIfRunning(port: Int, tomlPorts: List<Int>): Boolean {
        val running = DaemonProbe.healthVersion(port) ?: return true
        val key = AdminSupport.mgmtKey()
        if (key == null) {
            println("splice: mgmt-key not found at ${StatePaths().mgmtKeyFile} — can't stop the daemon")
            return false
        }
        val scope = stopScope(DaemonProbe.headPorts(port, key), tomlPorts)
        if (scope.degraded) {
            println(
                "splice: WARNING — could not enumerate this daemon's head ports (config unreadable and " +
                    "/api/heads unreachable). The stop check can only see :$port, so a head still " +
                    "holding its port may go unnoticed and the new daemon can hit EADDRINUSE.",
            )
        }
        println("splice: stopping daemon $running on :$port…")
        return daemonStop.stopDaemon(port, key, scope.ports).also { stopped ->
            if (!stopped) println("splice: the daemon did not stop — terminate it manually and retry")
        }
    }

    internal fun stopScope(livePorts: List<Int>?, tomlPorts: List<Int>): StopScope {
        val ports = (livePorts.orEmpty() + tomlPorts).distinct()
        return StopScope(ports, degraded = ports.isEmpty())
    }

    // The daemon reads api-key env vars from ITS OWN environment. A key exported after the daemon
    // booted is present in this shell but invisible upstream — the single most confusing first-run
    // trap, so doctor names it explicitly. Lives here because this verb IS the fix (FIX_RESTART).
    // When the daemon is UP but the daemon-side comparison can't run (no mgmt-key, or /api/auth
    // unreachable), the flagship check would silently vanish exactly when the daemon is busiest —
    // so emit an explicit WARN instead of empty. A STOPPED daemon is a plain skip (no noise).
    internal fun splitBrainChecks(
        heads: List<DoctorHeadAuth>,
        snapshot: DaemonSnapshot,
        envReader: EnvReader,
    ): List<DoctorCheck> {
        if (!snapshot.running) return emptyList()
        val key = AdminSupport.mgmtKey(envReader)
        val daemonSees = key?.let { DaemonProbe.authPresence(snapshot.port, it) }
        if (daemonSees == null) {
            val reason = if (key == null) "no mgmt-key" else "daemon /api/auth unreachable"
            return listOf(DoctorCheck("daemon-auth", CheckStatus.WARN, "daemon-side auth check skipped: $reason"))
        }
        return heads.filter { it.present && it.envVar != null && daemonSees[it.key] == false }.map { auth ->
            DoctorCheck(
                auth.key,
                CheckStatus.FAIL,
                "${auth.envVar} is set in this shell but the daemon started without it",
                FIX_RESTART,
            )
        }
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
