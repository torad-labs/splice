// NEW: the doctor's split-brain diagnosis, split from RestartCommand.kt (concentration, 2026-09-14):
// an api-key exported in this shell that the running daemon started without. `splice restart` is
// the fix, which is why RestartCommand still exposes it.
package splice.app.cli

import splice.app.DaemonProbe
import splice.core.util.EnvReader

internal class SplitBrainChecks {

    // The daemon reads api-key env vars from ITS OWN environment. A key exported after the daemon
    // booted is present in this shell but invisible upstream — the single most confusing first-run
    // trap, so doctor names it explicitly. Lives here because this verb IS the fix (FIX_RESTART).
    // When the daemon is UP but the daemon-side comparison can't run (no mgmt-key, or /api/auth
    // unreachable), the flagship check would silently vanish exactly when the daemon is busiest —
    // so emit an explicit WARN instead of empty. A STOPPED daemon is a plain skip (no noise).
    internal fun checks(
        heads: List<DoctorHeadAuth>,
        snapshot: DaemonSnapshot,
        envReader: EnvReader,
    ): List<DoctorCheck> {
        if (!snapshot.running) return emptyList()
        // DR-174: "no mgmt-key" was also this check's word for a key it simply could not read, so
        // the flagship split-brain diagnosis blamed a missing file on a box where one exists.
        val read = AdminSupport.readMgmtKey(envReader)
        val key = (read as? MgmtKeyRead.Present)?.key
        val daemonSees = key?.let { DaemonProbe.authPresence(snapshot.port, it) }
        if (daemonSees == null) {
            val reason = when {
                read is MgmtKeyRead.Unreadable -> "mgmt-key unreadable (${read.reason}) — fix its permissions"
                key == null -> "no mgmt-key"
                else -> "daemon /api/auth unreachable"
            }
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
