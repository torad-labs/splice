// NEW: V4-220 item 3 (2026-09-25) — the ONE decision every restart the daemon takes on goes through:
// the console's restart button (DaemonRoutes) and a console add's save. It was DaemonRoutes' own code
// until the add needed the same answer, and a second copy of it is how one of the two would come to
// skip the compaction wait or drain a daemon nothing brings back.
//
// The order is DaemonRoutes': an unwired supervision probe refuses; a supervised daemon drains
// under its unit, while an unsupervised daemon arms one detached successor before taking the drain.
// Both routes then wait for a compaction in flight or answer that the caller may drain.
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
    private val lock = Any()
    private var successor: DaemonSuccessor? = null
    private var successorArmed = false

    /** Assigned after the control server exists, before it binds its listener. */
    public fun wireSuccessor(value: DaemonSuccessor) {
        synchronized(lock) { successor = value }
    }

    /** Takes a restart on; [now] skips the compaction wait, as `splice restart --now` does. */
    public fun take(now: Boolean, shutdown: ShutdownDaemon, supervised: DaemonSupervised?): RestartTaken =
        synchronized(lock) {
            when {
                supervised == null -> RestartTaken.Refused(RESTART_SUPERVISION_UNWIRED, unwired = true)
                supervised() -> RestartTaken.Accepted(restart.request(now, shutdown))
                successor == null -> RestartTaken.Refused(RESTART_UNSUPERVISED, unwired = false)
                !successorArmed && successor?.start() != true ->
                    RestartTaken.Refused("could not arm a detached restart; the daemon remains up", unwired = false)
                else -> {
                    successorArmed = true
                    RestartTaken.Accepted(restart.request(now, shutdown))
                }
            }
        }

    /** Where a restart taken on stands. */
    public fun phase(): RestartPhase = restart.phase()
}
