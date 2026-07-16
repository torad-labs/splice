// NEW: the OpenAI-platform provider — same openai-responses dialect as codex (the shared-dialect
// bet paying off: zero new translator code), but api-key auth (api.openai.com, OPENAI_API_KEY)
// instead of ChatGPT OAuth, no ChatGPT-Account-ID header, first-message-hash cache key, summary
// supported. Proves the dialect is reused across THREE auth/quirk profiles (codex/xai/openai).
package splice.provider.openai

import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.TurnMeta
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.EffortLadder
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestBuilder
import splice.dialect.responses.ResponsesStreamTranslator
import splice.dialect.responses.StreamTurnContext
import splice.spi.BuiltTurn
import splice.spi.Provider
import splice.spi.StreamTranslator
import splice.spi.WatchdogFired

@Suppress("LongParameterList") // a provider bundles catalog+auth+quirks+config
public class OpenAiResponsesProvider(
    override val key: String,
    override val label: String,
    override val catalog: ModelCatalog,
    override val pinnedModel: String,
    override val auth: RefreshableAuthProvider,
    baseUrl: String,
    override val watchdog: WatchdogBudget,
    override val showReasoning: String,
    override val replayReasoning: Boolean,
    private val configEffort: String?,
    private val configSummary: String?,
) : Provider {

    override val upstreamUrl: String = "$baseUrl/responses"

    private val quirks = ResponsesQuirks(
        providerTag = "openai",
        store = false,
        cacheKeyStrategy = CacheKeyStrategy.FIRST_MESSAGE_HASH,
        effortLadder = EffortLadder.CODEX,
        supportsSummary = true,
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
                configSummary = configSummary,
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

    // api key rides as the standard bearer (UpstreamClient maps Credentials.ApiKey); no account header
    override fun extraHeaders(creds: Credentials): Map<String, String> = mapOf("Accept" to "text/event-stream")
}
