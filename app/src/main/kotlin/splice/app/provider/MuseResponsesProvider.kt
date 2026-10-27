// NEW: Muse keeps its per-head Responses alias map and static headers apart from OAuth account wiring.
package splice.app.provider

import splice.core.auth.Credentials
import splice.core.turn.TurnMeta
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.provider.muse.MuseGatewayRoutes
import splice.upstream.ProviderTuning
import splice.upstream.ToolNameShortener
import splice.upstream.VendorRoute

internal data class MuseResponsesOptions(
    val reasoning: ReasoningSettings,
    val quirks: ResponsesQuirks,
    val headers: Map<String, String>,
    val toolNames: ToolNameShortener,
    /** Where Meta's gateway runs each conversation of this head (the muse module owns when one moves). */
    val routes: MuseGatewayRoutes,
)

internal class MuseResponsesProvider(
    tuning: ProviderTuning,
    private val options: MuseResponsesOptions,
) : ResponsesProvider(
    tuning,
    options.reasoning,
    options.quirks,
    toolNames = options.toolNames,
    // Muse's own answers (a soft reset on a 429, a plan limit in Muse's words) before the Responses standard.
    replies = HeadReplies.muse,
) {
    override fun extraHeaders(creds: Credentials): Map<String, String> = options.headers

    override fun perTurnHeaders(meta: TurnMeta): Map<String, String> =
        conversation(meta)?.let { options.routes.headers(it) }.orEmpty()

    override fun route(meta: TurnMeta): VendorRoute? = conversation(meta)?.let { options.routes.route(it) }

    /** The same conversation key as prompt_cache_key; no client identity or raw session ID rides. Null when the cache
     *  key is off or the turn names no conversation, and then no route is pinned. */
    private fun conversation(meta: TurnMeta): String? {
        if (options.quirks.backend.promptCache.key == CacheKeyStrategy.OFF) return null
        return meta.scope.sessionId?.let { "${options.quirks.providerTag}:$it" } ?: meta.scope.conversationKey
    }
}
