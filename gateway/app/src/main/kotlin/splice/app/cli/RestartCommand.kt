// NEW: `splice restart` — stop the running daemon (stale or current) and cold-start it from THIS
// shell. The daemon reads api-key env vars from its own environment, so a key exported after the
// daemon booted is invisible until a restart — this verb is the documented fix for that trap
// (doctor and the launch warning both point here). :app is wall-exempt for println.
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.config.StatePaths

internal fun restart(): Boolean {
    // Load topology once: controlPort AND head ports come from it. The head ports feed the stop
    // check so a restart never declares success while a head port is still bound (F3).
    val topology = runCatching { TopologyLoader.loadOrMaterialize(TopologyLoader.configPath()) }.getOrNull()
    val port = AdminSupport.controlPort(topology)
    val headPorts = topology?.heads?.values?.map { it.port } ?: emptyList()
    if (!stopIfRunning(port, headPorts)) return false
    val started = AdminSupport.ensureDaemon(port)
    if (started) println("splice: daemon restarted with this shell's environment")
    return started
}

private fun stopIfRunning(port: Int, headPorts: List<Int>): Boolean {
    val running = ControlPlaneClient.healthVersion(port) ?: return true
    val key = AdminSupport.mgmtKey()
    return if (key == null) {
        println("splice: mgmt-key not found at ${StatePaths().mgmtKeyFile} — can't stop the daemon")
        false
    } else {
        println("splice: stopping daemon $running on :$port…")
        ControlPlaneClient.stopDaemon(port, key, headPorts).also { stopped ->
            if (!stopped) println("splice: the daemon did not stop — terminate it manually and retry")
        }
    }
}
