// NEW: the Provider SPI (plan). The generic head hosting in :gateway consumes THIS; concrete
// providers (:provider-codex/grok/openai) implement it by wiring their dialect translators +
// auth + quirks. This is why :gateway never sees a concrete dialect — the module law forces it.
package splice.spi

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.WatchdogBudget
import splice.core.parse.AnthropicTurnBody

/** A per-turn stream state machine: drives the WireSink from upstream events, returns an outcome
 *  AFTER the loop (cross-event state + harvest). Imperative, not a Flow operator (see the dialect). */
public fun interface StreamTranslator {
    public suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome
}

/** The upstream request the provider built from an Anthropic body: wire JSON + per-turn meta. */
public data class BuiltTurn(val requestBody: JsonObject, val meta: TurnMeta)

/** Everything the generic head needs to serve one provider. */
public interface Provider {
    public val key: String
    public val label: String
    public val catalog: ModelCatalog
    public val pinnedModel: String
    public val auth: RefreshableAuthProvider
    public val upstreamUrl: String
    public val watchdog: WatchdogBudget
    public val showReasoning: String
    public val replayReasoning: Boolean

    public fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn

    public fun streamTranslator(meta: TurnMeta, watchdogFired: () -> WatchdogFired?): StreamTranslator

    public fun extraHeaders(creds: Credentials): Map<String, String>
}
