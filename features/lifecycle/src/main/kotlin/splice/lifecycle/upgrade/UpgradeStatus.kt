// PORT-OF: daemon/control/.../ControlPorts.kt (UpgradeStatus) — invariants unchanged: the upgrade
// surface's port, moved beside the route that answers with it.
package splice.lifecycle.upgrade

/**
 * V4-127: what the upgrade surface knows — installed, latest, whether a rollback is available —
 * rendered as JSON text for the console's upgrade bay.
 *
 * A separate role from `DoctorReport` (the diagnostics feature) despite the identical shape: one is a DIAGNOSIS of the running
 * daemon, the other is a VERSION question about the artifact on disk, and they are answered by
 * different code with different failure modes.
 */
public fun interface UpgradeStatus {
    public operator fun invoke(): String
}
