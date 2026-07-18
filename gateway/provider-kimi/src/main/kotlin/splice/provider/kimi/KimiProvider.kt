// NEW: the Kimi (Moonshot) Provider — the anthropic-passthrough dialect against api.kimi.com/coding,
// which speaks the Anthropic Messages wire natively. Delegation shape mirrors GrokProvider: the
// PassthroughRequestBuilder scrubs the request to Kimi's accepted shape, the PassthroughStreamTranslator
// re-indexes the upstream Anthropic SSE onto the WireSink (synthesizing thinking-block signatures Kimi
// never sends). Invariants: upstreamUrl = "${baseUrl}/v1/messages" (baseUrl carries no /v1 — topology
// supplies it); auth is Credentials.ApiKey with header x-api-key (NOT Authorization/Bearer), so this
// provider NEVER sets an Authorization header; extraHeaders carry the KimiDeviceIdentity X-Msh-* set
// plus the proven-accepted UA "KimiCLI/1.5" (the coding endpoint 403s unrecognized UAs). Passthrough
// emits REAL thinking blocks, so showReasoning stays "off" — the text mirror must not double-render.
package splice.provider.kimi

import splice.core.auth.Credentials
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.dialect.passthrough.PassthroughQuirks
import splice.dialect.passthrough.PassthroughRequestBuilder
import splice.dialect.passthrough.PassthroughStreamTranslator
import splice.dialect.passthrough.PassthroughTurnContext
import splice.spi.BuiltTurn
import splice.spi.Provider
import splice.spi.ProviderIdentity
import splice.spi.ProviderTuning
import splice.spi.StreamTranslator
import splice.spi.TurnSignals

public class KimiProvider(
    private val tuning: ProviderTuning,
    private val identity: KimiDeviceIdentity,
) : Provider, ProviderIdentity by tuning {

    // baseUrl is https://api.kimi.com/coding (NO /v1); the Anthropic Messages path is /v1/messages.
    override val upstreamUrl: String = "${tuning.baseUrl}/v1/messages"

    // Passthrough emits native thinking blocks, so the transcript text-mirror stays off and there is
    // no encrypted-reasoning replay on the Anthropic surface.
    override val showReasoning: ReasoningDisplay = ReasoningDisplay.OFF
    override val replayReasoning: Boolean = false

    private val builder = PassthroughRequestBuilder(PassthroughQuirks(providerTag = tuning.key))

    override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
        val upstreamModel = catalog.stripSuffixes(body.typed.model)
        val built = builder.build(
            body = body,
            upstreamModel = upstreamModel,
            originalModel = body.typed.model,
            compact = compact,
        )
        return BuiltTurn(built.req, built.meta)
    }

    override fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator =
        PassthroughStreamTranslator(
            PassthroughTurnContext(
                clientGone = signals.clientGone,
                watchdogFired = signals.watchdogFired,
                idleCapMs = watchdog.streamIdle.inWholeMilliseconds,
                totalCapMs = watchdog.totalCap.inWholeMilliseconds,
            ),
        )

    // Auth arrives as Credentials.ApiKey(x-api-key) — UpstreamClient applies it; we add NO Authorization.
    override fun extraHeaders(creds: Credentials): Map<String, String> = buildMap {
        put("Accept", "text/event-stream")
        put("anthropic-version", ANTHROPIC_VERSION)
        // The coding endpoint 403s unrecognized UAs; this exact value is proven-accepted.
        put("User-Agent", KIMI_USER_AGENT)
        putAll(identity.headers())
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val KIMI_USER_AGENT = "KimiCLI/1.5"
    }
}
