// PORT-OF: splice/gateway/head/TurnDriver.kt (TurnWiring, reduced to usagePayloadBuilder)
// @ 86f1411 — invariants unchanged: the Anthropic usage payload builder, shared by the stream
// emitter and the non-stream collector so both report tokens identically. timedClientWrite left
// with the class' other half to ClientChannel.kt (HD-24) — kept in splice.gateway.wire because
// [UsagePayloadBuilder], the fun interface this produces, is declared in this package
// (SseEmitter.kt) and consumed by SseEmitter and CollectingTerminal.
package splice.gateway.wire

import splice.core.model.ModelCatalog
import splice.core.turn.TurnMeta
import splice.gateway.usage.TurnUsage
import splice.gateway.usage.UsageHud

/** A collaborator (not TurnDriver members) because TurnDriver already sits at its function-count
 *  budget. No instance state. */
internal class TurnWiring {
    private val hud = UsageHud()

    /** The Anthropic usage payload builder — shared by the stream emitter and the non-stream
     *  collector so both report tokens identically. */
    fun usagePayloadBuilder(catalog: ModelCatalog, meta: TurnMeta): UsagePayloadBuilder = { usage ->
        // Anthropic convention (Claude Code HUD/autocompact): input_tokens and cache_read_input_tokens
        // are DISJOINT. OpenAI's input_tokens INCLUDES the cached portion, so subtract it — else
        // input+cache_read double-counts and the context bar/autocompact fire ~2x early (the
        // "compaction ate my quota" class).
        val cached = usage?.cachedTokens ?: 0
        val nonCachedInput = ((usage?.inputTokens ?: 0) - cached).coerceAtLeast(0)
        // Per-model context windows are a PROXY concern. Claude Code fixes its window per PROCESS
        // (the launch env) for every id except a "[1m]" one, so a row wanting any other window can
        // only be served from this side — by moving the numerator of the ratio it compacts on.
        // Scale by clientWindow/declared and the row compacts at ITS window, switchable live from
        // /model. Keyed on originalModel (the RAW picker id), because two rows can share one
        // upstream id and it is the row that owns the window. Output tokens are NOT scaled: they
        // are not part of the context total. splice's own accounting (TurnPerf, TurnCacheLine,
        // UsageStore) reads the raw usage and never this payload, so the logs stay truthful.
        //
        // The STATUSLINE does not: StatuslineRenderer renders context_window_size/current_usage
        // out of the blob Claude Code pipes back, which is Claude Code's record of THIS payload, so
        // on a scaled row both of its context numbers are in client units. Its cache-hit segment is
        // unaffected — read/(input+read+cache_creation) is scale-invariant when every term carries
        // the same factor. Nothing logs the factor itself, so a WRONG factor is self-consistent
        // everywhere it is displayed; that is the residual risk, tracked, not a claim of safety.
        val scale = catalog.usageScale(meta.originalModel)
        hud.buildUsagePayload(
            TurnUsage(scale(nonCachedInput, scale), usage?.outputTokens ?: 0, 0, scale(cached, scale)),
            catalog.clientContextWindowFor(meta.originalModel),
        )
    }

    /** Exact when the row agrees with the client, which keeps every normal head byte-identical. */
    private fun scale(tokens: Long, factor: Double): Long =
        if (factor == 1.0) tokens else (tokens * factor).toLong()
}
