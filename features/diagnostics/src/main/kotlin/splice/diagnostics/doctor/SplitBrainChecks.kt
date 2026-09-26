// NEW: the doctor's split-brain diagnosis, split from RestartCommand.kt (concentration, 2026-09-14):
// an api-key exported in this shell that the running daemon started without. `splice restart` is
// the fix (FIX_RESTART); the check itself is doctor's and moved with it to features/diagnostics
// (LAYOUT-01), reading the mgmt key and /api/auth through the daemon client. V4-220 item 6b: that read
// happens once, here, and DoctorAuth also reads the client heads' upstream verdict from it.
package splice.diagnostics.doctor

import splice.core.util.EnvReader
import splice.daemonclient.DaemonProbe

/** What the running daemon's `/api/auth` said, read ONCE per doctor run: the client heads' verdict
 *  lines and the split-brain comparison both read it (V4-220 item 6b). */
internal sealed class DaemonAuthSeen {
    /** The daemon is not running: a plain skip, no noise. */
    data object Stopped : DaemonAuthSeen()

    /** The daemon is up and its side could not be read, for [reason]. */
    data class Skipped(val reason: String) : DaemonAuthSeen()

    data class Seen(val heads: Map<String, DaemonProbe.HeadAuthSeen>) : DaemonAuthSeen()
}

internal class SplitBrainChecks {

    // DR-174: "no mgmt-key" was also this check's word for a key it simply could not read, so
    // the flagship split-brain diagnosis blamed a missing file on a box where one exists.
    internal fun read(snapshot: DaemonSnapshot, envReader: EnvReader, reads: DaemonReads): DaemonAuthSeen {
        if (snapshot.probe == DaemonProbe.HealthProbe.Down) return DaemonAuthSeen.Stopped
        snapshot.unanswered?.let { return DaemonAuthSeen.Skipped(it) }
        return when (val seen = reads.auth(snapshot.port, envReader)) {
            is DaemonRead.Answered -> DaemonAuthSeen.Seen(seen.value)
            is DaemonRead.KeyUnreadable ->
                DaemonAuthSeen.Skipped("mgmt-key unreadable (${seen.reason}); fix its permissions")
            DaemonRead.KeyAbsent -> DaemonAuthSeen.Skipped("no mgmt-key")
            DaemonRead.Unreachable -> DaemonAuthSeen.Skipped("daemon /api/auth unreachable")
        }
    }

    // The daemon reads api-key env vars from ITS OWN environment. A key exported after the daemon
    // booted is present in this shell but invisible upstream — the single most confusing first-run
    // trap, so doctor names it explicitly. The restart verb IS the fix (FIX_RESTART).
    // When the daemon is UP but the daemon-side comparison can't run (no mgmt-key, or /api/auth
    // unreachable), the flagship check would silently vanish exactly when the daemon is busiest —
    // so emit an explicit WARN instead of empty. A STOPPED daemon is a plain skip (no noise).
    internal fun checks(heads: List<DoctorHeadAuth>, daemon: DaemonAuthSeen): List<DoctorCheck> = when (daemon) {
        DaemonAuthSeen.Stopped -> emptyList()
        is DaemonAuthSeen.Skipped ->
            listOf(DoctorCheck("daemon-auth", CheckStatus.WARN, "daemon-side auth check skipped: ${daemon.reason}"))
        is DaemonAuthSeen.Seen ->
            heads.filter { it.present && it.envVar != null && daemon.heads[it.key]?.present == false }.map { auth ->
                DoctorCheck(
                    auth.key,
                    CheckStatus.FAIL,
                    "${auth.envVar} is set in this shell but the daemon started without it",
                    FIX_RESTART,
                )
            }
    }
}
