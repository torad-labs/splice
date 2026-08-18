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
import splice.core.util.LogSink
import splice.gateway.wire.BufferingWireSink
import splice.spi.FoldController
import splice.spi.FoldRound
import splice.spi.ReanchorController
import splice.spi.ReanchorRound
import splice.spi.ToolSearchController

internal class FoldRounds(
    private val key: String,
    private val log: LogSink,
    private val reanchor: ReanchorController?,
    private val signals: RunnerSignals,
    private val toolSearch: ToolSearchController?,
    private val finish: FinishTurn,
    private val rounds: RoundSplice,
) {
    /** The fold-continuation and search-continuation checks, extracted out of FoldRunner.run
     *  (detekt 2026-07-24: LongMethod/CyclomaticComplexMethod/LoopWithTooManyJumpStatements). Null =
     *  neither fired; the caller falls through to the re-anchor check exactly as before the
     *  extraction. */
    fun nextRoundBody(
        fold: FoldController,
        outcome: TurnOutcome,
        buffer: BufferingWireSink,
        salvaged: MutableList<TurnOutcome.PartialRound>,
        cursor: RoundCursor,
    ): RoundCursor? {
        val (body, roundIndex, searchIndex) = cursor
        val success = outcome as? TurnOutcome.Success
        val foldNext = success?.let { fold.continuation(FoldRound(body, it, roundIndex)) }
        if (foldNext != null) {
            buffer.discard()
            // CX-09: a fold round whose reasoning reached the client must say so, or a turn whose
            // FINAL round comes back empty is graded "nothing reached the client" and errors after
            // the user already saw thinking (BufferingWireSink forwards openThinking/thinkingDelta
            // straight to the real sink). Pre-dates CX-09; closed here because it is its class.
            // thinkingText is deliberately NOT carried: a fold continuation re-accumulates the
            // whole turn's reasoning, so merging this round's copy in duplicates it — that is the
            // 2026-07-26 mirror-duplication incident, and HeadServerFoldTest's summary-dedup case
            // fails immediately if you try. usage is not carried either; the caller folds it into
            // `acc` separately. ONLY the honesty flag rides, which no other field can express.
            salvaged.add(TurnOutcome.PartialRound(emittedThinking = success.emittedThinking))
            log(
                "[$key] fold round ${roundIndex + 1}: reasoning truncated at " +
                    "${success.usage.reasoningTokens} tokens, continuing\n",
            )
            return RoundCursor(foldNext, roundIndex + 1, searchIndex)
        }
        // FoldRunner's rounds run through a BufferingWireSink — reducer.emittedText/bodyText
        // reflect what the round PRODUCED, not what reached the client. Strip both before the
        // controller ever sees them, so a buffered-and-discarded round can never "vouch" for
        // prose the client never saw in the search continuation's own replay (review 2026-07-24;
        // the same rule this loop's own re-anchor branch below already applies).
        val searchNext = rounds.searchContinuation(
            toolSearch,
            rounds.bufferedForSearch(outcome),
            body,
            searchIndex,
            signals,
        )
        if (searchNext != null) {
            buffer.discard() // the buffered final output never reached the client
            salvaged.add(rounds.searchPartial(outcome as TurnOutcome.Success, buffered = true))
            val nextSearchIndex = searchIndex + 1
            signals.onSearchRound(nextSearchIndex)
            log("[$key] tool search round $nextSearchIndex: answering locally, continuing\n")
            return RoundCursor(searchNext, roundIndex, nextSearchIndex)
        }
        return null
    }

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
