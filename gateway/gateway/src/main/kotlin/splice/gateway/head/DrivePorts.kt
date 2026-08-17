// NEW: the turn-drive ROLES of the head, named (HD-22, wave 4b).
//
// TurnDriver's round runners are built out of injected decisions — post a round, finish the turn,
// is the client still there, did the watchdog trip — and every one of them arrived as a raw
// function type. Two runners (FoldRunner, ReanchorRunner) declare the same set separately, and
// RunnerSignals groups four more, so a single misplaced argument between same-shaped neighbours
// compiled silently. These are the names.
//
// WHAT IS DELIBERATELY NOT HERE. `clientGone` and `clientFrameEmitted` are NOT redeclared: they are
// [splice.spi.ClientGone] and [splice.spi.ClientFrameEmitted], the same questions the SPI already
// names, and TurnDriver wires the very same values into UpstreamClient — `clientFrameEmitted =
// frameEmittedThisRound` at the post() call site is the proof, so this wave unified those two
// spellings onto one type rather than minting a head-local twin.
//
// WHY [WatchdogTripped] IS NOT [splice.spi.WatchdogProbe]. They ask different questions and return
// different types: the SPI's probe returns the [splice.spi.WatchdogFired] REASON, because a
// translator has to end the stream with the specific budget that blew; this one is a plain boolean
// gate on whether a runner may start another round, and no reason it could carry would change the
// answer. Same word in the parameter name, two roles, so two types — measured at the seams, not
// inferred from the name.
package splice.gateway.head

import splice.core.turn.TurnOutcome
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
 * Whether the watchdog has already fired on this turn — the runner's gate on starting ANOTHER
 * round.
 *
 * A boolean and not a reason: see this file's header. A fire never continues; its cancellation owns
 * the turn from that point, and this exists so a runner between rounds notices rather than issuing
 * one more upstream request against a turn that is already over.
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

/**
 * Classifies a round that produced ZERO upstream events into an outcome.
 *
 * Given the drive, the outcome so far, the captured body text and the event baseline: a zero-event
 * round is exactly the case where the upstream sent something that is not SSE at all — an HTML or
 * JSON login page from a dead head — so the classification depends on the captured raw body and not
 * on the event stream that never existed.
 */
internal fun interface ZeroEventClassifier {
    operator fun invoke(drive: TurnDrive, outcome: TurnOutcome, bodyText: String, eventsBase: Long): TurnOutcome
}

/**
 * The request decode-and-translate work run while a materialization lease is held.
 *
 * The lease bounds the short, allocation-heavy phase that simultaneously holds the raw UTF-8 body,
 * the Anthropic tree and the translated tree — so what belongs inside one of these is exactly that
 * phase, and the long-lived upstream turn (bounded by the separate turn gate) does not. Something
 * that held its lease across the upstream call would starve every other head's materialization.
 *
 * `T : Any` at the fast-fail entry point is not incidental: null there means CONTENTION and nothing
 * else, so a legitimately-null result is made unrepresentable rather than ambiguous.
 */
public fun interface MaterializedRequest<T> {
    public suspend operator fun invoke(): T
}
