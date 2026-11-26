// PORT-OF: splice/gateway/head/TurnDrive.kt (TurnInputs) @ 86f1411 — invariants unchanged:
// admission-time inputs threaded into a drive. Own file (concentration, 2026-08-19) so TurnDrive
// is not billed for a second column-0 type.
package splice.head.turn

import splice.core.failure.TurnVoice
import splice.core.memory.HeapLease
import splice.core.model.UsageSizing
import splice.core.perf.TurnPerf
import splice.head.admission.KeyNaming
import splice.head.live.LiveReply
import splice.head.port.HarnessRequest
import splice.head.usage.QuotaTracker
import splice.head.wire.NATIVE_CLIENT_PING_MS
import splice.head.wire.TurnTrace
import splice.upstream.BuiltTurn
import splice.upstream.credentials.AccountSelection
import splice.upstream.retry.InflightGate
import splice.upstream.retry.OLD_CLIENT_WALL_MS

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
 *  [TurnSlot.held] stays the admission's to release unless this fires.
 *
 *  `invoke` is spelled as the single abstract method so the call site stays `inputs.slot.markHandedOff()`
 *  and the wiring stays a lambda literal (SAM conversion); adopting the port is a declaration diff,
 *  never a rename storm. */
internal fun interface HandoffMark {
    operator fun invoke()
}

/** Admission-time inputs threaded into a drive — grouped so the drive assembler stays one
 *  cohesive argument across the stream and collect entries. */
internal data class TurnInputs(
    val built: BuiltTurn,
    val slot: TurnSlot,
    val t0: Long,
    val perf: TurnPerf,
    /** V4-174: this turn's trace, begun at admission for a head whose trace is on; null records
     *  nothing. REQUIRED, NO DEFAULT, for [TurnSlot.markHandedOff]'s reason: the wiring is the control. */
    val trace: TurnTrace?,
    val accountQuota: TurnAccountQuota = TurnAccountQuota(),
    /** Failure-handling slice 2: the voice every line this turn ends with is spoken in, built once at admission from
     *  the request's own facts (a compaction, a first exchange). REQUIRED, NO DEFAULT: a turn that spoke in some
     *  other turn's voice would say the wrong thing about its own request. */
    val voice: TurnVoice,
) {
    val clientWallMs: Long get() = slot.clientWallMs
    val heapLease: HeapLease? get() = slot.heapLease
    val harness: HarnessRequest? get() = slot.harness?.request
    val rebuild: TurnRebuild? get() = slot.harness?.rebuild
    val live: LiveReply? get() = slot.reply.live
}

/** Usage units and the live view of the reply a client receives. */
internal data class TurnReply(val usage: UsageSizing = UsageSizing(), val live: LiveReply? = null)

/** The admission slot a turn holds, the admission-time facts that travel with it, and the mark its drive calls when
 *  a detached compaction takes that slot with it. One value because the mark speaks only of this slot (grouped when slice 2's voice joined [TurnInputs]). */
internal data class TurnSlot(
    val held: InflightGate.Slot,
    /** The client deadline and retained request travel with this admission slot until it is released. */
    val clientWallMs: Long = OLD_CLIENT_WALL_MS,
    /** How the client is paced while it waits and once it has seen content (ClientPace). */
    val pace: ClientPace = ClientPace(),
    val heapLease: HeapLease? = null,
    /** What the harness's capabilities read of the request this slot was admitted for (docs/specs/harness.md,
     *  decision 2); null reads nothing. It travels with the slot as the client deadline and the retained request do. */
    val harness: HarnessAsked? = null,
    val reply: TurnReply = TurnReply(),
    /** Called by TurnStreamer when a detached compaction takes [held] with it: the drive releases
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
)

/** How a turn's client is paced: [pingMs], how often it is pinged while it waits, the native disconnect probe unless a
 *  listener in front of the gateway asked for a shorter keepalive (GatewayTurns.clientPingMs); and [streamDeadlineMs],
 *  the deadline such a listener set on a silence once the client has seen content (GatewayTurns.streamDeadlineMs,
 *  #791), null on every other turn. Both are read off the call at admission, so they travel together. */
internal data class ClientPace(val pingMs: Long = NATIVE_CLIENT_PING_MS, val streamDeadlineMs: Long? = null)

/** The login a turn runs on and the quota tracker that login's usage lands in. They are chosen together and
 *  replaced together, so one value carries both. */
internal data class TurnAccountQuota(
    val account: AccountSelection? = null,
    val quota: QuotaTracker? = null,
    /** The key this head reads, for the account a row records when no login was proved (KeyAccount): its name,
     *  no account at all when the key is gone, or None to leave the head's own fallback label standing. */
    val keyNaming: KeyNaming = KeyNaming.None,
)
