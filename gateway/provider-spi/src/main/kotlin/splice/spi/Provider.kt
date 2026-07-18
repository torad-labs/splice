// NEW: the Provider SPI (plan). The generic head hosting in :gateway consumes THIS; concrete
// providers (:provider-codex/grok/openai) implement it by wiring their dialect translators +
// auth + quirks. This is why :gateway never sees a concrete dialect — the module law forces it.
package splice.spi

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.WatchdogBudget

/** A per-turn stream state machine: drives the WireSink from upstream events, returns an outcome
 *  AFTER the loop (cross-event state + harvest). Imperative, not a Flow operator (see the dialect). */
public fun interface StreamTranslator {
    public suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome
}

/** The upstream request the provider built from an Anthropic body: wire JSON + per-turn meta. */
public data class BuiltTurn(val requestBody: JsonObject, val meta: TurnMeta)

/** The dialect-invariant identity a provider exposes: which head it is, its catalog, auth, budget.
 *  Every concrete provider shares this exact cluster, so it's grouped (see [ProviderTuning]) and
 *  delegated instead of re-threaded through each constructor. */
public interface ProviderIdentity {
    public val key: String
    public val label: String
    public val catalog: ModelCatalog
    public val pinnedModel: String
    public val auth: RefreshableAuthProvider
    public val watchdog: WatchdogBudget
}

/** The construction bundle every concrete provider takes: its [ProviderIdentity] plus the upstream
 *  base URL each provider turns into its own [Provider.upstreamUrl]. One cohesive param in place of
 *  the seven knobs that were identical across codex/grok/openai. */
public data class ProviderTuning(
    override val key: String,
    override val label: String,
    override val catalog: ModelCatalog,
    override val pinnedModel: String,
    override val auth: RefreshableAuthProvider,
    val baseUrl: String,
    override val watchdog: WatchdogBudget,
) : ProviderIdentity

/** Everything the generic head needs to serve one provider. */
public interface Provider : ProviderIdentity {
    public val upstreamUrl: String
    public val showReasoning: String
    public val replayReasoning: Boolean

    public fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn

    public fun streamTranslator(meta: TurnMeta, watchdogFired: () -> WatchdogFired?): StreamTranslator

    public fun extraHeaders(creds: Credentials): Map<String, String>
}
