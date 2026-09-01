// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: these DECIDE NOTHING —
// they project accumulated turn state into TurnOutcome.Success / TurnOutcome.PartialRound. The one
// code both the success path and the salvage path share.
package splice.dialect.responses

import splice.core.turn.TurnOutcome
import splice.core.turn.Usage

internal class ResponsesOutcomePayload(private val ctx: StreamTurnContext) {

    private val harvest = ResponsesHarvest()

    fun successOutcome(state: ResponsesTurnState): TurnOutcome = TurnOutcome.Success(
        hasToolUse = state.hasToolUse,
        incomplete = state.incomplete,
        usage = Usage(
            state.inputTokens,
            state.outputTokens,
            state.cachedTokens,
            state.reasoningTokens,
        ),
        thinkingText = state.thinkingBuf.toString(),
        bodyText = state.textBuf.toString(),
        emittedText = state.emittedText,
        emittedThinking = state.emittedThinking,
        reasoningEnvelopes = state.reasoningEnvelopes.toList(),
        // The harvest fallback runs ONLY when the streamed list is empty (needs no dedup): a round
        // that emitted only a search call and was missed by the live capture would otherwise
        // produce a client-visible empty turn through the honesty gate — the worst available failure.
        toolSearches = state.toolSearches.ifEmpty { harvest.harvestToolSearchCalls(state.finalResponse) },
    )

    /** The salvage payload for mid-stream re-anchoring — the wire is at a block boundary
     *  (driveTurn closeAll precedes the terminal decision); watchdog failures never carry one
     *  (their turn coroutine is being cancelled — nothing may re-POST). Compact turns never
     *  re-anchor, so they skip the full-buffer copies — but they keep the BURN (DR-130): the
     *  usage [ResponsesEventReducer] harvests from `response.failed` (its own words: "so the
     *  salvage is not permanently zero") reaches the usage store and the perf counts only
     *  through this payload, and a compaction is the most expensive turn class there is. A
     *  usage-only partial cannot cause a re-POST: a compact turn has no re-anchor controller at
     *  all, which is exactly why it took the un-salvaged path. */
    fun partialOrNull(state: ResponsesTurnState): TurnOutcome.PartialRound? =
        if (ctx.compact) TurnOutcome.PartialRound(usage = usageOf(state)) else partialRound(state)

    private fun partialRound(state: ResponsesTurnState): TurnOutcome.PartialRound = TurnOutcome.PartialRound(
        thinkingText = state.thinkingBuf.toString(),
        bodyText = state.textBuf.toString(),
        emittedText = state.emittedText,
        emittedThinking = state.emittedThinking,
        hasToolUse = state.hasToolUse,
        reasoningEnvelopes = state.reasoningEnvelopes.toList(),
        toolTearOpen = state.toolSalvage.tearOpen,
        usage = usageOf(state),
    )

    /** The round's harvested burn. One reader for both payload shapes (DR-130) so the compact
     *  carve-out above cannot drift from the full one. */
    private fun usageOf(state: ResponsesTurnState): Usage = Usage(
        inputTokens = state.inputTokens,
        outputTokens = state.outputTokens,
        cachedTokens = state.cachedTokens,
        reasoningTokens = state.reasoningTokens,
    )
}
