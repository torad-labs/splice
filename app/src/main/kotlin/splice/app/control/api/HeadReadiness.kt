// NEW: the head set's verdict, the one /health reads for ok and for the ready and failed counts. The ok contract and
// the invariant readyHeads + failedHeads == configured heads live here, unchanged from the health body they were
// carved out of.
package splice.app.control.api

import splice.app.control.FailedHeads
import splice.app.control.ManagedHead
import splice.app.control.TurnPathStalled

internal class HeadReadiness(
    private val heads: Map<String, ManagedHead>,
    private val failedHeads: FailedHeads,
    private val configuredHeads: Int,
    private val turnPathStalled: TurnPathStalled,
) {
    /** One read of the head set: the verdict, and the counts and reasons the health report writes beside it. */
    fun snapshot(): Snapshot {
        // ok means "this gateway can serve", not "heads are configured" — the 91h wedge served
        // ok:true for its entire duration under the old hardcoded value (2026-08-12). Precisely: no
        // head is unresponsive at its request path, none failed to start, and at least one is up.
        // It is NOT an end-to-end turn assertion — see TurnPathProbeLoop's header for the probe's
        // documented ceiling (it is answered at the 401 before the gate and driver).
        //
        // F4: only a head that is SUPPOSED to be running can drag ok false. A deliberate
        // `POST /api/heads/x/stop` makes the probe see connection-refused and mark the head
        // stalled, but that is an intentional state, not the wedge — intersecting with the
        // running set keeps an operator's maintenance stop from paging an external monitor.
        //
        // ...but "not running" is NOT self-certifying. The F4 intersection alone read a head that
        // CRASHED or never started as "not supposed to be running", so its stall entry was
        // discarded and a daemon whose every head died on EADDRINUSE served
        // {ok:true, readyHeads:0, failedHeads:4} — the same green-through-an-outage shape as the
        // 91h wedge, one layer over. failedHeads() is what separates a crash from a deliberate
        // stop, and a configured daemon with nothing running cannot complete a turn either way.
        val runningKeys = heads.filterValues { it.head.healthSnapshot().running }.keys
        val stalled = turnPathStalled().filter { it in runningKeys }
        val running = runningKeys.size
        val failed = failedHeads()
        return Snapshot(
            ok = stalled.isEmpty() && failed == 0 && (configuredHeads == 0 || running > 0),
            running = running,
            failed = failed,
            reasons = failedHeads.reasons(),
            configured = configuredHeads,
            stalled = stalled,
        )
    }

    internal data class Snapshot(
        val ok: Boolean,
        val running: Int,
        val failed: Int,
        val reasons: Map<String, String>,
        val configured: Int,
        val stalled: List<String>,
    )
}
