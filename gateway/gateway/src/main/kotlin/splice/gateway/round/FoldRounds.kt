// PORT-OF: splice/gateway/head/TurnDriver.kt (FoldRunner.nextRoundBody,
// FoldRunner.continuationForFailedRound, FoldRunner.finalize) @ 86f1411 — invariants unchanged:
// the fold-continuation, search-continuation and re-anchor-continuation checks FoldRunner.run
// dispatches through, plus the single finalization. Split into its own class (HD-24) so
// FoldRunner.kt stays the loop only; FoldRunner constructs and owns one FoldRounds sharing its
// own RoundSplice instance.
package splice.gateway.round

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.gateway.wire.BufferingWireSink
import splice.spi.FoldController
import splice.spi.ReanchorController
import splice.spi.ReanchorRound
import splice.spi.RetryNotice
import splice.spi.ToolSearchController

internal class FoldRounds(
    key: String,
    log: RetryNotice,
    private val reanchor: ReanchorController?,
    private val signals: RunnerSignals,
    toolSearch: ToolSearchController?,
    private val finish: FinishTurn,
    private val rounds: RoundSplice,
) {
    private val continuations = FoldContinuations(key, log, signals, toolSearch, rounds)

    fun nextRoundBody(
        fold: FoldController,
        outcome: TurnOutcome,
        buffer: BufferingWireSink,
        salvaged: MutableList<TurnOutcome.PartialRound>,
        cursor: RoundCursor,
    ): RoundCursor? = continuations.nextRoundBody(fold, outcome, buffer, salvaged, cursor)

    fun continuationForFailedRound(outcome: TurnOutcome, body: JsonObject, attempt: Int): JsonObject? =
        when {
            reanchor == null || outcome !is TurnOutcome.Failure -> null
            signals.watchdogFired() || signals.clientGone() -> null
            else -> reanchor.continuationForFailure(
                ReanchorRound(body, outcome.copy(partial = outcome.partial?.copy(bodyText = "")), attempt),
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
