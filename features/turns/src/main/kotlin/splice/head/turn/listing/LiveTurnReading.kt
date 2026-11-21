// NEW: Nov 21, 2026 — split from LiveTurns.kt along its seam: what one live turn reads as when the console lists it
// (its silence, and whether splice would carry it on), apart from the registry that lists and stops the turns.
package splice.head.turn.listing

import splice.core.perf.TurnPerf
import splice.head.turn.TurnState

/** How a turn's provider silence reads: how long quiet, and whether there is anything to be quiet
 *  after. ONE VALUE BECAUSE NEITHER IS READABLE ALONE.
 *
 *  The watchdog measures silence against a different limit depending on [seenOutput]
 *  ([splice.core.turn.WatchdogBudget]): before the first byte the limit is firstByteTimeout, and a
 *  prefill is *legitimately silent for minutes*; after it, the limit is the re-anchor tier when the
 *  round can be resumed and streamIdle otherwise. [idleMs] does not differ between the two, because a
 *  turn that has heard nothing reports its whole age as idle — so a reader given the number alone
 *  draws a two-minute prefill, which is a model thinking, exactly like a two-minute stall, which is a
 *  model that stopped. Those are opposite answers: one says wait, the other says go look.
 *
 *  [seenOutput] IS NOT THE GATE'S PHASE, though that is the obvious place to reach for. The gate turns
 *  [splice.core.head.GatePhase.STREAMING] on `touch()`, which upstream HEADERS call — a turn whose
 *  provider sent a 200 and then nothing reads as streaming there while the client has seen no content
 *  at all. That is right for the gate, whose job is liveness of the connection, and wrong here: it
 *  would push a turn into the stall tiers while the watchdog still holds it against firstByteTimeout.
 *  So this follows `received()`, the body-byte signal, exactly as [idleMs] does, and the two cannot
 *  disagree with each other. */
public data class TurnSilence(
    /** Time since the provider's last byte, or since this turn was listed before its first byte. */
    val idleMs: Long,
    /** Whether the provider has answered at all yet. */
    val seenOutput: Boolean = false,
    /** How many times splice has already ended a silence on this turn by cancelling the round and
     *  re-POSTing it from its own salvage, carrying on invisibly to the client. Zero for a turn that
     *  has run straight through. Here because a resume is what a silence ends in, so it is read with
     *  [idleMs] and never apart from it.
     *
     *  READ OFF [splice.core.perf.PerfKeys.REANCHORS], THE COUNTER THE RE-ANCHOR ITSELF WRITES
     *  (DriveSignals.onReanchor). THE GATE CANNOT ANSWER THIS, though it looks like it can, and the
     *  first build of this field was wrong for exactly that reason. `InflightGate.Slot.resumedSource`
     *  marks a borrowed handle over a source's counted permit, and `AdmittedTurn.settle` keeps such a
     *  handle only when `roundInterceptor.resumesSource()` is true — which in production only the
     *  codex code-mode bridge ever answers, when a LATER CLIENT REQUEST joins an upstream stream
     *  splice still holds. That is the opposite direction of travel from a re-anchor: the client sent
     *  it and knows about it. And the re-anchor never passes through admission at all, because it
     *  lives inside the round loop (Watchdog, RetryMatrix, the dialects' ReanchorPolicy), so no gate
     *  handle is ever borrowed for one. Counted off the gate, this read was 0 forever on every head
     *  but one, and counted something else on that one.
     *
     *  IT IS A COUNT OF WHAT HAPPENED, NOT A PREDICTION OF WHAT WILL. A reader deciding whether THIS
     *  silence is about to end in another re-send needs more than this and more than the head's armed
     *  tier: the round must also still be eligible, and one of those conditions — whether the round has
     *  already emitted a tool call — lives inside the dialect's own round state, which this seam cannot
     *  see. So this says how many resumes a turn has had, which is true, and says nothing about the
     *  next one, which would not be. */
    val resumes: Int = 0,
    /** How many times the upstream REFUSED this turn and splice re-POSTed it: a 429 with budget left,
     *  an overload, a 5xx, a transport error before any output (UpstreamAttempt.markRetry, read off
     *  [splice.core.perf.PerfKeys.RETRIES]).
     *
     *  A DIFFERENT NUMBER FROM [resumes] AND NOT INTERCHANGEABLE WITH IT, which is why both are here
     *  rather than one sum. A retry means the provider would not take the request; a resume means it
     *  took it, began answering, and went quiet. Different cause, different remedy: a retried turn is
     *  waiting on capacity that is not ours, and a resumed turn is being carried by splice. A reader
     *  shown one under the other's name would act on the wrong one. */
    val retries: Int = 0,
    /** Whether a failure of this round right now would still be carried on by splice: the head has a re-anchor tier,
     *  the dialect has a rule, the continuation budget is not spent, and the round has not emitted a tool call. The
     *  dialect decides (ReanchorPolicy.wouldContinue); false is the Stalled card's "Won't resume". */
    val willResume: Boolean = false,
)

/** Whether splice would carry on a turn's round if it failed now, read when the console lists the turn. The
 *  reading belongs to the dialect's re-anchor rule (see the admission's WillResume); the listing only asks. */
internal fun interface ResumeReading {
    fun now(perf: TurnPerf): Boolean
}

/** One live turn as the console lists it. [session] is the client's full session id when it sent one;
 *  [stopped] is true between the stop and the slot's release, which is the seal's few milliseconds. [state] says
 *  whether the turn runs or is held. */
public data class LiveTurn(
    val id: String,
    val session: String?,
    val model: String,
    val stopped: Boolean,
    val silence: TurnSilence,
    /** Running, or held in its own request until its cause lifts (failure-handling slice 5). */
    val state: TurnState = TurnState.Running,
)
