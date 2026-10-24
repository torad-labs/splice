// PORT-OF: splice/gateway/head/TurnDriver.kt (FoldRunner.nextRoundBody,
// FoldRunner.continuationForFailedRound, FoldRunner.finalize) @ 86f1411 — invariants unchanged:
// the fold-continuation, search-continuation and re-anchor-continuation checks FoldRunner.run
// dispatches through, plus the single finalization. Split into its own class (HD-24) so
// FoldRunner.kt stays the loop only; FoldRunner constructs and owns one FoldRounds sharing its
// own RoundSplice instance.
package splice.head.round

import kotlinx.serialization.json.JsonObject
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
    private val reanchor: ReanchorPolicy? = null,
    toolSearch: ToolSearchPolicy? = null,
) {
    private val rounds = RoundSplice()
    private val continuations = FoldContinuations(key, log, signals, toolSearch, rounds)

    /** [outcome] with the tokens a failed round billed carried onto it (DR-124), [summed] being the
     *  usage of the rounds absorbed so far. */
    fun withFailureSalvage(outcome: TurnOutcome, summed: RoundUsage): TurnOutcome =
        rounds.withFailureSalvage(outcome, summed)

    /** The fold re-anchor line: round [attempt] (zero-based) died mid-stream and is being retried. */
    fun noteReanchor(attempt: Int, failure: TurnOutcome.Failure) =
        log("[$key] fold re-anchor ${attempt + 1}: ${failure.type.wireName} mid-round; retrying\n")

    /** Health for the rounds this fold absorbed, once the turn ends on something other than a failure. */
    fun reportAbsorbed(failures: List<TurnOutcome.Failure>) = failures.forEach(signals.onRoundFailure::invoke)

    fun nextRoundBody(
        fold: FoldPolicy,
        outcome: TurnOutcome,
        buffer: BufferingWireSink,
        salvaged: MutableList<TurnOutcome.PartialRound>,
        cursor: RoundCursor,
    ): RoundCursor? = continuations.nextRoundBody(fold, outcome, buffer, salvaged, cursor)

    fun continuationForFailedRound(outcome: TurnOutcome, body: JsonObject, attempt: Int): JsonObject? =
        when {
            reanchor == null || outcome !is TurnOutcome.Failure -> null
            // DR-7: the watchdog veto is GONE; clientGone stays. A watchdog fire used to end the
            // turn here unconditionally, which made the salvage above unreachable by construction.
            // A stalled round is exactly the case worth continuing — the client is still listening
            // and the round left real reasoning behind. A client that HAS gone is still never
            // continued: there is nobody to continue for.
            signals.clientGone() -> null
            else -> reanchor.continuationForFailure(
                ReanchorRound(
                    body,
                    outcome.copy(partial = outcome.partial?.let { it.copy(text = it.text.copy(bodyText = "")) }),
                    attempt,
                ),
            )
        }

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
