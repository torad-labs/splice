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
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.WatchdogBudget

/** A per-turn stream state machine: drives the WireSink from upstream events, returns an outcome
 *  AFTER the loop (cross-event state + harvest). Imperative, not a Flow operator (see the dialect). */
public fun interface StreamTranslator {
    public suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome
}

/** The upstream request the provider built from an Anthropic body: wire JSON + per-turn meta +
 *  per-turn extra HTTP headers (e.g. grok's x-grok-conv-id — PER TURN, never provider-shared
 *  state: a shared field races concurrent sessions into each other's affinity headers). */
public data class BuiltTurn(
    val requestBody: JsonObject,
    val meta: TurnMeta,
    val extraHeaders: Map<String, String> = emptyMap(),
)

/** Per-turn liveness signals the gateway hands the translator: the watchdog's typed sentinel and
 *  a REAL client-liveness probe (flipped when a downstream write fails — the head owns it; a
 *  provider must never hardcode it, that makes ClientAbandoned unreachable dead code). */
public data class TurnSignals(
    val watchdogFired: () -> WatchdogFired?,
    val clientGone: () -> Boolean,
)

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

    /** The per-head `<command> login` instruction (empty when the provider has no OAuth login
     *  flow, e.g. api-key-only heads) — surfaced by [splice.gateway.head.TurnDriver] as an
     *  operator hint on AUTHENTICATION-classified failures. */
    public val loginCommand: String
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
    override val loginCommand: String = "",
) : ProviderIdentity

/** Everything the generic head needs to serve one provider. */
public interface Provider : ProviderIdentity {
    public val upstreamUrl: String
    public val showReasoning: ReasoningDisplay
    public val replayReasoning: Boolean

    public fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn

    public fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator

    public fun extraHeaders(creds: Credentials): Map<String, String>

    /** Reasoning-continuation folding for this turn, or null when the feature is off for this
     *  model/head (the default — every non-codex provider stays pure passthrough). */
    public fun foldController(meta: TurnMeta): FoldController? = null

    /** Mid-stream re-anchoring policy for FAILED rounds; null = surface the failure (pre-reanchor behaviour). */
    public fun reanchorController(meta: TurnMeta): ReanchorController? = null
}
