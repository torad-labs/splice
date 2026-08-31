// NEW: fold-continuation and search-continuation checks, split from FoldRounds
// (concentration, 2026-08-19) so neither file is billed for the other's methods.
// Same-package.
package splice.gateway.round

import splice.core.turn.TurnOutcome
import splice.gateway.wire.BufferingWireSink
import splice.spi.FoldController
import splice.spi.FoldRound
import splice.spi.RetryNotice
import splice.spi.ToolSearchController

internal class FoldContinuations(
    private val key: String,
    private val log: RetryNotice,
    private val signals: RunnerSignals,
    private val toolSearch: ToolSearchController?,
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
        // DR-89: the same refusal the search branch (RoundSplice.searchContinuation) and the
        // trigger-B failed-round branch already apply — a gone client or a fired watchdog must
        // not buy more upstream fold rounds (quota burn + a pinned slot for a reader that left).
        val signalsClear = !signals.watchdogFired() && !signals.clientGone()
        val foldNext = if (signalsClear) success?.let { fold.continuation(FoldRound(body, it, roundIndex)) } else null
        if (foldNext != null && success != null) {
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
}
