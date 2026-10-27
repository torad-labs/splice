// PORT-OF: splice/gateway/head/TurnDriver.kt (FoldRunner.nextRoundBody,
// FoldRunner.continuationForFailedRound, FoldRunner.finalize) @ 86f1411 — invariants unchanged:
// the fold-continuation, search-continuation and re-anchor-continuation checks FoldRunner.run
// dispatches through, plus the single finalization. Split into its own class (HD-24) so
// FoldRunner.kt stays the loop only; FoldRunner constructs and owns one FoldRounds sharing its
// own RoundSplice instance.
package splice.head.round

import kotlinx.serialization.json.JsonObject
import splice.core.failure.ClientWire
import splice.core.failure.Recovery
import splice.core.failure.WaitLine
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.head.wire.BufferingWireSink
import splice.upstream.FoldPolicy
import splice.upstream.ReanchorPolicy
import splice.upstream.ReanchorRound
import splice.upstream.RetryNotice
import splice.upstream.ToolSearchPolicy

internal class FoldRounds(
    private val key: String,
    private val log: RetryNotice,
    private val signals: RunnerSignals,
    private val finish: FinishTurn,
    /** The turn's steps: a fold round that fails mid-stream is a REANCHOR like any other, on the turn's one ledger
     *  (final-design.md section 8). */
    private val steps: RoundSteps,
    private val reanchor: ReanchorPolicy? = null,
    toolSearch: ToolSearchPolicy? = null,
) {
    private val rounds = RoundSplice()
    private val continuations = FoldContinuations(key, log, signals, toolSearch, rounds, steps)

    /** [outcome] with the tokens a failed round billed carried onto it (DR-124), [summed] being the
     *  usage of the rounds absorbed so far. */
    fun withFailureSalvage(outcome: TurnOutcome, summed: RoundUsage): TurnOutcome =
        rounds.withFailureSalvage(outcome, summed)

    /** The fold re-anchor: round [attempt] (zero-based) died mid-stream and continues from its salvage. The journal
     *  names the attempt; the turn is told the failure, which a person reads as the episode's line (slice 2). */
    suspend fun noteReanchor(attempt: Int, failure: TurnOutcome.Failure) {
        val type = ClientWire.ownType(failure.cause).wireName
        log("[$key] fold re-anchor ${attempt + 1}: $type mid-round; retrying\n")
        signals.recovering(WaitLine(failure.cause, Recovery.Resume))
    }

    /** Health for the rounds this fold absorbed, once the turn ends on something other than a failure. */
    fun reportAbsorbed(failures: List<TurnOutcome.Failure>) = failures.forEach(signals.onRoundFailure::invoke)

    suspend fun nextRoundBody(
        fold: FoldPolicy,
        outcome: TurnOutcome,
        buffer: BufferingWireSink,
        salvaged: MutableList<TurnOutcome.PartialRound>,
        cursor: RoundCursor,
    ): RoundCursor? = continuations.nextRoundBody(fold, outcome, buffer, salvaged, cursor)

    /** The request that carries a failed round on, with the engine's step for it; null when the head's re-anchor rule
     *  cannot carry it on from what arrived or the engine ends the turn there. */
    fun continuationForFailedRound(outcome: TurnOutcome, body: JsonObject, attempt: Int): FailureContinuation? {
        val failure = outcome as? TurnOutcome.Failure ?: return null
        // DR-7: the watchdog veto is GONE; clientGone stays. A watchdog fire used to end the
        // turn here unconditionally, which made the salvage above unreachable by construction.
        // A stalled round is exactly the case worth continuing — the client is still listening
        // and the round left real reasoning behind. A client that HAS gone is still never
        // continued: there is nobody to continue for.
        if (reanchor == null || signals.clientGone()) return null
        val stripped = failure.copy(partial = failure.partial?.let { it.copy(text = it.text.copy(bodyText = "")) })
        return reanchor.continuationForFailure(ReanchorRound(body, stripped, attempt))?.let { next ->
            steps.recovery(failure)?.let { FailureContinuation(next, failure, it) }
        }
    }

    /** The engine's step for [retry] taken: its budget spent in the turn's ledger, then its wait. */
    suspend fun take(retry: FailureContinuation) = steps.take(retry.step, retry.failure.cause, retry.failure.at)

    suspend fun finalize(
        outcome: TurnOutcome,
        buffer: BufferingWireSink,
        salvaged: List<TurnOutcome.PartialRound>,
        summed: Usage,
    ) {
        if (outcome is TurnOutcome.Success) {
            buffer.flush()
            finish(rounds.mergedAcrossRounds(outcome.copy(usage = summed), salvaged))
        } else {
            // a failed/abandoned round has no honest final output to flush — drop the buffer, then
            // emit the round's real (error/abandon) outcome. Never a fabricated clean stop (L3).
            buffer.discard()
            finish(outcome)
        }
    }
}
