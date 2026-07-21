// NEW: `splice restart` — stop the running daemon (stale or current) and cold-start it from THIS
// shell. The daemon reads api-key env vars from its own environment, so a key exported after the
// daemon booted is invisible until a restart — this verb is the documented fix for that trap
// (doctor and the launch warning both point here). :app is wall-exempt for println.
package splice.app.cli

import splice.core.config.StatePaths

internal fun restart(): Boolean {
    val port = AdminSupport.controlPort()
    if (!stopIfRunning(port)) return false
    val started = AdminSupport.ensureDaemon(port)
    if (started) println("splice: daemon restarted with this shell's environment")
    return started
}

private fun stopIfRunning(port: Int): Boolean {
    val running = ControlPlaneClient.healthVersion(port) ?: return true
    val key = AdminSupport.mgmtKey()
    return if (key == null) {
        println("splice: mgmt-key not found at ${StatePaths().mgmtKeyFile} — can't stop the daemon")
        false
    } else {
        println("splice: stopping daemon $running on :$port…")
        ControlPlaneClient.stopDaemon(port, key).also { stopped ->
            if (!stopped) println("splice: the daemon did not stop — terminate it manually and retry")
        }
    }
}
