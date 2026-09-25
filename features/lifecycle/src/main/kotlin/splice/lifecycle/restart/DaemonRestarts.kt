// NEW: V4-220 item 3 (2026-09-25) — the ONE decision every restart the daemon takes on goes through:
// the console's restart button (DaemonRoutes) and a console add's save. It was DaemonRoutes' own code
// until the add needed the same answer, and a second copy of it is how one of the two would come to
// skip the compaction wait or drain a daemon nothing brings back.
//
// The order is DaemonRoutes': an unwired supervision probe refuses, an unsupervised daemon refuses,
// and only then is the restart handed to RestartAfterCompactions, which waits for a compaction in
// flight or answers that the caller may drain.
package splice.lifecycle.restart

/** Whether a restart the daemon was asked to take on was taken on. */
public sealed class RestartTaken {
    /** Not taken, and no drain requested. [unwired] tells a wiring gap in this daemon from a true
     *  statement about how it was started: the fixes differ, so the answers do too. */
    public data class Refused(val reason: String, val unwired: Boolean) : RestartTaken()

    /** Taken on, in [phase]: [RestartPhase.Draining] means the CALLER requests the drain once its
     *  own answer is written; [RestartPhase.Waiting] means the wait requests it later. */
    public data class Accepted(val phase: RestartPhase) : RestartTaken()
}

public class DaemonRestarts(private val restart: RestartAfterCompactions) {

    /** Takes a restart on; [now] skips the compaction wait, as `splice restart --now` does. */
    public fun take(now: Boolean, shutdown: ShutdownDaemon, supervised: DaemonSupervised?): RestartTaken = when {
        supervised == null -> RestartTaken.Refused(RESTART_SUPERVISION_UNWIRED, unwired = true)
        !supervised() -> RestartTaken.Refused(RESTART_UNSUPERVISED, unwired = false)
        else -> RestartTaken.Accepted(restart.request(now, shutdown))
    }

    /** Where a restart taken on stands. */
    public fun phase(): RestartPhase = restart.phase()
}
