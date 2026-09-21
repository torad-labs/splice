// PORT-OF: splice/gateway/head/TurnDrive.kt (TurnInputs) @ 86f1411 — invariants unchanged:
// admission-time inputs threaded into a drive. Own file (concentration, 2026-08-19) so TurnDrive
// is not billed for a second column-0 type.
package splice.gateway.head

import splice.core.perf.TurnPerf
import splice.gateway.usage.QuotaTracker
import splice.gateway.wire.TurnTrace
import splice.upstream.BuiltTurn
import splice.upstream.credentials.AccountSelection
import splice.upstream.retry.InflightGate

/** The hand-off seam, NAMED (kt-no-lambda-seam, V4-99 item 3).
 *
 *  This was `() -> Unit` — a raw function type, which says how many arguments arrive and nothing
 *  about what the thing is FOR. The rule's own census is why that matters here rather than being a
 *  style preference: `() -> Boolean` and `() -> Unit` each carry several unrelated ROLES across this
 *  tree, and the compiler cannot tell one from another when the arities line up. A `fun interface`
 *  makes the mistake inexpressible and gives the seam a place to carry this paragraph.
 *
 *  The role, not the shape: the DRIVE calls it at the instant a detached compaction takes the
 *  admission slot with it. Nothing else may call it, and it is not a general "turn ended" hook —
 *  [TurnInputs.slot] stays the admission's to release unless this fires.
 *
 *  `invoke` is spelled as the single abstract method so the call site stays `inputs.markHandedOff()`
 *  and the wiring stays a lambda literal (SAM conversion); adopting the port is a declaration diff,
 *  never a rename storm. */
internal fun interface HandoffMark {
    operator fun invoke()
}

/** Admission-time inputs threaded into a drive — grouped so the drive assembler stays one
 *  cohesive argument across the stream and collect entries. */
internal data class TurnInputs(
    val built: BuiltTurn,
    val slot: InflightGate.Slot,
    val t0: Long,
    val perf: TurnPerf,
    /** Called by TurnStreamer when a detached compaction takes [slot] with it: the drive releases
     *  the slot when the upstream turn ends, and HeadAdmission's finally must then leave it alone.
     *
     *  A PORT, NOT AN ATOMICBOOLEAN THE DRIVE MUTATES (V4-99 item 3, law 19). Two reasons, and the
     *  second is why a returned Boolean alone is not enough:
     *  1. the same silent-omission shape the default used to hide — a caller that forgot to pass
     *     its own flag got one nobody read, so the hand-off looked like it had not happened;
     *  2. a CANCELLATION can throw out of `serve` before the caller reads any return value, and a
     *     returned Boolean dies with that throw. The port is called by the drive at the moment of
     *     the hand-off, so the caller's own flag is already set when the finally runs — which is
     *     the only path that matters for a detached compaction, since a head stop cancels exactly
     *     the calls that would otherwise read the return.
     *
     *  REQUIRED, NO DEFAULT: the wiring IS the control. */
    val markHandedOff: HandoffMark,
    /** V4-174: this turn's trace, begun at admission for a head whose trace is on; null records
     *  nothing. REQUIRED, NO DEFAULT, for [markHandedOff]'s reason: the wiring is the control. */
    val trace: TurnTrace?,
    val account: AccountSelection? = null,
    val quota: QuotaTracker? = null,
)
