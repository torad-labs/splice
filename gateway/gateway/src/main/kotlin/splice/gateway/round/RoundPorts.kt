// PORT-OF: splice/gateway/head/DrivePorts.kt (PostRoundToSink, PostRound, FinishTurn,
// WatchdogTripped, RoundFailureHook, SearchRoundCounter) + splice/gateway/head/TurnDriver.kt
// (RunnerSignals) @ 86f1411 — invariants unchanged: the six round-runner seams these name, plus
// the collaborator bundle that groups four of them for the runners' constructors.
//
// WHY THESE MOVED HERE (HD-24): the round runners (FoldRunner, ReanchorRunner and their
// collaborators) moved into splice.gateway.round because they are a self-contained state machine
// with zero dependence on TurnDrive/TurnDriver — the runners take only ports. The ports that name
// their seams belong beside the runners, not in the head package that no longer constructs them
// directly. ZeroEventClassifier and MaterializedRequest stayed behind in
// splice.gateway.head.DrivePorts.kt: their consumers (WsRoundDriver, RequestMaterializationGate)
// stayed in head, so the port stays with its consumer.
//
// WHY [WatchdogTripped] IS NOT [splice.spi.WatchdogProbe]. They ask different questions and return
// different types: the SPI's probe returns the [splice.spi.WatchdogFired] REASON, because a
// translator has to end the stream with the specific budget that blew; this one is a plain boolean
// gate on whether a runner may start another SUCCESS-side round, and on that question no reason it
// could carry would change the answer. Same word in the parameter name, two roles, so two types —
// measured at the seams, not inferred from the name.
//
// DR-7 found the one place where the reason DID change the answer, and the fix was to move that
// path off this port rather than to widen the port: a Failure re-anchor salvages a partial after an
// Idle round-reap but must not after a TotalCap, so [ReanchorContinuation.continuationForFailure]
// stopped consuming this boolean entirely and reads the reason from the SPI. If a third consumer
// ever needs to tell Idle from TotalCap, that is the same signal — take the reason, do not teach
// this boolean to carry one.
package splice.gateway.round

import splice.core.turn.TurnOutcome
import splice.spi.ClientGone
import splice.spi.WireSink

/**
 * POSTs one round of the turn and returns its honest outcome — the FoldRunner variant, which also
 * hands over the sink the round's frames go to.
 *
 * Fold buffers: a truncated round's output is DISCARDED and the next round is re-POSTed with its
 * reasoning replayed, so this may be invoked several times for one client-visible turn and only the
 * terminal round's frames are flushed. The sink parameter is what lets the runner point a round at
 * the buffer rather than at the client, which is the whole mechanism — see [PostRound] for the
 * re-anchor runner's sinkless sibling, which splices rounds onto a LIVE emitter instead.
 */
internal fun interface PostRoundToSink {
    suspend operator fun invoke(bodyJson: String, sink: WireSink): TurnOutcome
}

/**
 * POSTs one round of the turn and returns its honest outcome — the ReanchorRunner variant.
 *
 * No sink, and the absence is the difference: re-anchor rounds stream straight onto the live
 * emitter, whose seal and monotonic block indices make the spliced rounds one coherent Anthropic
 * message. Nothing to redirect, so nothing to pass.
 */
internal fun interface PostRound {
    suspend operator fun invoke(bodyJson: String): TurnOutcome
}

/**
 * Ends the turn downstream with its final outcome — called EXACTLY ONCE per turn, with usage summed
 * across every round.
 *
 * The once is the contract, and it is L3: one honest terminal downstream. Both runners take one,
 * both call it on every exit path including the failed ones, and a truncated or failed turn must
 * reach it with a Failure rather than reaching it with a success or not reaching it at all.
 */
internal fun interface FinishTurn {
    suspend operator fun invoke(outcome: TurnOutcome)
}

/**
 * Whether the watchdog has already fired on this turn — the gate on starting another SUCCESS-side
 * round. Its two live consumers are [FoldContinuations] (reasoning-continuation fold) and
 * [RoundSplice.searchContinuation] (tool search); both ask more work of a turn whose budget already
 * blew, which is why neither cares WHICH budget blew.
 *
 * A boolean and not a reason: see this file's header. It used to say "a fire never continues; its
 * cancellation owns the turn from that point", which described a watchdog that cancelled the whole
 * TURN. DR-7 made an Idle fire reap one ROUND, so the turn survives its own watchdog and a Failure
 * re-anchor may continue past one — that path no longer consults this port at all. What is left
 * true is narrower and still worth a gate: a runner between rounds must not issue one more upstream
 * request for MORE output on a turn that has already run out of budget.
 */
internal fun interface WatchdogTripped {
    operator fun invoke(): Boolean
}

/**
 * The health hook for a round failure the runner ABSORBED — a round that failed and was retried or
 * spliced over rather than surfacing to the client.
 *
 * It exists precisely because those failures are invisible downstream: without it a head can fail
 * every round and still report healthy turns. Not a decision — the runner has already chosen to
 * absorb the failure by the time this is called.
 */
internal fun interface RoundFailureHook {
    operator fun invoke(failure: TurnOutcome.Failure)
}

/**
 * Records how many search-continuation rounds this turn ran — the expected-delta instrument.
 *
 * Stamped as an ABSOLUTE count rather than incremented, which is the property that makes it usable
 * as verification: a turn that never searched records nothing at all, and a turn that did records
 * exactly N, so an unmoved counter is evidence rather than ambiguity.
 */
internal fun interface SearchRoundCounter {
    operator fun invoke(rounds: Int)
}

/** Shared per-loop collaborators for the round runners: liveness gates + the health hook for
 *  absorbed failures (one construction site in TurnDriveFactory.assembleDrive — the policies never
 *  drift apart). */
internal data class RunnerSignals(
    val watchdogFired: WatchdogTripped = WatchdogTripped { false },
    val clientGone: ClientGone = ClientGone { false },
    val onRoundFailure: RoundFailureHook = RoundFailureHook {},
    /** Search-continuation counter sink — the expected-delta instrument. Stamped as an absolute
     *  count, so a turn that never searched records nothing and a turn that did records exactly N. */
    val onSearchRound: SearchRoundCounter = SearchRoundCounter {},
)
