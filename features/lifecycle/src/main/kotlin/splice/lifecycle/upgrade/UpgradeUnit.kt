// NEW: V4-220 item 4 (2026-09-25) — the host's user unit as `splice upgrade`'s restart sees it, moved
// out of UpgradeDaemon.restart unchanged when that method took the compaction wait.
package splice.lifecycle.upgrade

import java.nio.file.Path

/** The unit named [userUnit] (V4-176: from the knob layer; splice does not own it and cannot assume one
 *  is there), asked through systemctl. */
internal class UpgradeUnit(private val userUnit: String, private val process: UpgradeProcess) {

    /** The unit supervises this install when its ExecStart names [liveJar] AND it is active, so the daemon
     *  on the port is its process: a loaded but inactive unit means a daemon started by hand, and an
     *  install under another share dir (a second copy, a test home) never restarts someone else's unit. */
    fun supervises(liveJar: Path): Boolean {
        val unit = process(listOf("systemctl", "--user", "show", "-p", "ExecStart", "--value", userUnit), false)
        val active = process(listOf("systemctl", "--user", "is-active", userUnit), false)
        return unit.code == 0 && unit.stdout.contains(liveJar.toString()) && active.stdout.trim() == "active"
    }

    /** Restarts the unit; its stop is the daemon's own drain. */
    fun restart() {
        process(listOf("systemctl", "--user", "restart", userUnit), true)
    }
}
