// NEW: the codex Provider — wires the shared openai-responses dialect (builder + stream
// translator) with codex quirks (chatgpt-oauth, account_id header, first-message-hash cache key,
// max effort ceiling, summary supported, spark drops summary) and the reasoning-envelope codec.
// This is the concrete impl the module law keeps out of :gateway; :app instantiates it.
package splice.provider.codex

import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.TurnMeta
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestBuilder
import splice.dialect.responses.ResponsesStreamTranslator
import splice.dialect.responses.StreamTurnContext
import splice.spi.BuiltTurn
import splice.spi.Provider
import splice.spi.StreamTranslator
import splice.spi.WatchdogFired

@Suppress("LongParameterList") // a provider bundles catalog+auth+quirks+config — all load-bearing
public class CodexProvider(
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
    private val quirks: ResponsesQuirks = ResponsesQuirks(providerTag = "claudex"),
    private val accountIdHeader: Boolean = true,
) : Provider {

    override val upstreamUrl: String = "$baseUrl/responses"
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

    override fun extraHeaders(creds: Credentials): Map<String, String> = buildMap {
        put("Accept", "text/event-stream")
        val accountId = (creds as? Credentials.Bearer)?.accountId
        if (accountIdHeader && accountId != null) {
            put("ChatGPT-Account-ID", accountId)
        }
    }
}
