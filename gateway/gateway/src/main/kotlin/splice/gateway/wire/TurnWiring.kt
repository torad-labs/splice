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
        hud.buildUsagePayload(
            TurnUsage(nonCachedInput, usage?.outputTokens ?: 0, 0, cached),
            catalog.contextWindowFor(meta.upstreamModel),
        )
    }
}
