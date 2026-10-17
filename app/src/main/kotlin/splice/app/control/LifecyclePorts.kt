// The daemon-lifecycle ports of [ConsolePorts]: the upgrade status, its check and its run, and whether systemd
// owns this daemon. Moved out of ConsolePorts.kt (concentration, Oct 12): the four are one subject, the daemon's
// own version and restart, and they were the only reason ConsolePorts named splice.lifecycle.
package splice.app.control

import splice.lifecycle.restart.DaemonSupervised
import splice.lifecycle.upgrade.UpgradeChecks
import splice.lifecycle.upgrade.UpgradeRuns
import splice.lifecycle.upgrade.UpgradeStatus

/** The upgrade and restart ports, kept to [ConsolePorts]' discipline: each is assigned by ConsoleWiring after the
 *  server is constructed, read at CALL time through a lambda, and NULL MEANS UNWIRED, answered with a named 5xx. */
internal class LifecyclePorts {
    /** V4-127: the upgrade status GET /api/upgrade reports. Null answers a named 503, never "nothing to upgrade". */
    var upgrade: UpgradeStatus? = null

    /** The Version card's "Check": the snapshot GET /api/upgrade/check reports and the read POST makes. Null answers a named 503. */
    var upgradeChecks: UpgradeChecks? = null

    /** V4-220 item 4: `splice upgrade` as the console starts it, and the run it reports. Null answers
     *  a named 503 on POST /api/upgrade and GET /api/upgrade/run, never a run that never started. */
    var upgradeRuns: UpgradeRuns? = null

    /** V4-137: whether systemd owns this daemon. A supervised daemon drains into its unit; an unsupervised one
     *  arms a detached successor before draining.
     *
     *  NULL IS NOT "ASSUME SUPERVISED". The route refuses an unwired probe: choosing the unit for a
     *  daemon started by hand would drain it without a successor. */
    var supervised: DaemonSupervised? = null
}
