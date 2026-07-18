// NEW: the grok Provider — the SAME openai-responses dialect as codex (verified in the Node
// inventory: grok's translators were Responses-dialect, not chat), with grok quirks: api-key
// auth (Authorization: Bearer <key>), session-id cache key (claude-grok:<x-claude-code-session-
// id>), effort ceiling high (clamps xhigh/max → high, floors to low, can't disable), NO summary
// field (grok auto-exposes summaries via the stream), compact effort low, tool_choice emitted.
// P0-XAI (anthropic-passthrough fidelity) is credential-blocked, so this is the proven fallback:
// the exact quirks the Node grok/translate-request.mjs used. Reuses the shared dialect + machine
// — the multi-provider abstraction pays off (zero new translator code, only quirks + auth).
package splice.provider.grok

import splice.core.auth.Credentials
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.TurnMeta
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.EffortLadder
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestBuilder
import splice.dialect.responses.ResponsesStreamTranslator
import splice.dialect.responses.StreamTurnContext
import splice.spi.BuiltTurn
import splice.spi.Provider
import splice.spi.ProviderIdentity
import splice.spi.ProviderTuning
import splice.spi.StreamTranslator
import splice.spi.WatchdogFired

public class GrokProvider(
    private val tuning: ProviderTuning,
    override val showReasoning: String,
    override val replayReasoning: Boolean,
    private val configEffort: String?,
) : Provider, ProviderIdentity by tuning {

    override val upstreamUrl: String = "${tuning.baseUrl}/responses"

    private val quirks = ResponsesQuirks(
        providerTag = "claude-grok",
        store = false,
        cacheKeyStrategy = CacheKeyStrategy.SESSION_ID,
        effortLadder = EffortLadder.GROK,
        supportsSummary = false,
        summaryRejectModelRegex = null,
        compactEffortPin = "low",
        emitToolChoice = true,
        emitStrict = true,
    )
    private val builder = ResponsesRequestBuilder(quirks)

    override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
        val upstreamModel = catalog.stripSuffixes(body.typed.model)
        val built = builder.build(
            body.typed,
            body.raw,
            BuildOptions(
                compact = compact,
                originalModel = body.typed.model,
                upstreamModel = upstreamModel,
                configEffort = configEffort,
                configSummary = null,
                showReasoning = showReasoning,
                replayReasoning = replayReasoning,
                sessionId = sessionId,
                decodeReasoningEnvelope = { splice.core.reasoning.decodeReasoningEnvelope(it) },
            ),
        )
        return BuiltTurn(built.req, built.meta)
    }

    override fun streamTranslator(meta: TurnMeta, watchdogFired: () -> WatchdogFired?): StreamTranslator =
        ResponsesStreamTranslator(
            StreamTurnContext(
                compact = meta.compact,
                replayReasoning = replayReasoning,
                encodeReasoningEnvelope = { splice.core.reasoning.encodeReasoningEnvelope(it) },
                clientGone = { false },
                watchdogFired = watchdogFired,
                streamIdleMsForMessage = watchdog.streamIdle.inWholeMilliseconds,
                upstreamTimeoutMsForMessage = watchdog.totalCap.inWholeMilliseconds,
            ),
        )

    override fun extraHeaders(creds: Credentials): Map<String, String> = buildMap {
        put("Accept", "text/event-stream")
        // xAI api key rides as the standard bearer; the session-id cache key is in the body
    }
}
