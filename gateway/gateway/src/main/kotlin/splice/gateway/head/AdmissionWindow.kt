// NEW: the head's admission window — one owner for the flag that says whether new turns are
// accepted (HD-24). Both readers (the front-door check and the post-acquire re-check, now in
// AdmissionGate) and the single writer (HeadServer.stopLocked) went through one field on
// HeadServer; giving the flag its own holder keeps that invariant intact across the split without
// the route table having to reach back into the lifecycle owner.
package splice.gateway.head

/**
 * Open while the head serves turns; closed for the whole of the stop drain.
 *
 * stopLocked closes this BEFORE draining so the drain can converge — with admission open, the
 * drain window just filled with new turns that were then cancelled anyway (review 2026-07-22).
 * Convergence needs every path to the gate honoring it: the front-door acceptingOrRespond check
 * AND the post-acquire re-check inside acquireSlotOrRespond, so a waiter the InflightGate promotes
 * mid-drain is bounced too rather than starting a turn the engine stop would kill (review
 * 2026-07-22 round 3). Rejected turns get the 529 capacity shape, so clients retry and land after
 * the restart.
 */
internal class AdmissionWindow {
    @Volatile
    private var accepting = true

    val isOpen: Boolean get() = accepting

    fun open() {
        accepting = true
    }

    fun close() {
        accepting = false
    }
}
