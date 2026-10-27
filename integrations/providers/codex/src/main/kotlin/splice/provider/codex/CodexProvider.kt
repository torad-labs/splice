// NEW: the codex Provider — the shared openai-responses base (ResponsesProvider) with codex quirks
// (chatgpt-oauth, account_id header, first-message-hash cache key, max effort ceiling, summary
// supported, spark drops summary). The reasoning-policy wiring lives in the base; this class adds
// ONLY the ChatGPT-Account-ID header, codex-rs's per-turn routing/session headers
// (CodexRoutingHeaders) and the codex quirk profile.
package splice.provider.codex

import splice.core.auth.Credentials
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.TurnMeta
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.failure.ResponsesReplies
import splice.dialect.responses.request.ResponsesToolResultMedia
import splice.dialect.responses.stream.LiveFoldConfig
import splice.dialect.responses.websocket.CODEX_TURN_STATE_HEADER
import splice.dialect.responses.websocket.ResponsesWebSocketPolicy
import splice.provider.codex.failure.CodexReplies
import splice.provider.codex.request.CodexRequestConstraints
import splice.upstream.BuiltTurn
import splice.upstream.ProviderTuning
import splice.upstream.RequestRefusal
import splice.upstream.failure.Replies
import splice.upstream.transport.UpstreamResponse

public class CodexProvider(
    tuning: ProviderTuning,
    reasoning: ReasoningSettings,
    quirks: ResponsesQuirks = CodexQuirks().defaultQuirks(),
    // Reasoning-continuation folding (codex 518n-2). null = off; the daemon wires it from config.
    foldConfig: LiveFoldConfig? = null,
    private val accountIdHeader: Boolean = true,
    /** Daemon log sink — forwarded to ResponsesProvider so its diagnostics reach
     *  /mgmt/logs and not stderr alone (wall kt-no-println, 2026-07-27). */
    log: LogSink = LogSink(DaemonLog::write),
    private val codeMode: CodexCodeModeWiring = CodexCodeModeWiring(),
) : ResponsesProvider(
    tuning,
    reasoning,
    quirks,
    foldConfig,
    log,
    // Install this head's reading before the base initializes its Responses terminal handling.
    replies = Replies(ResponsesReplies, CodexReplies),
) {

    private val codeModeTurns = codeMode.turnBuilder(ResponsesToolResultMedia(quirks))

    /** Proven against the live ChatGPT backend by the WS-0 spike
     *  (.dev/research/spikes/responses-websocket.md): handshake, event vocabulary and
     *  previous_response_id chaining all confirmed. No other Responses upstream has been probed. */
    override val supportsWebSocket: Boolean = true

    private val routing = CodexRoutingHeaders()

    /** codex-rs's per-turn routing/session headers — the measurement is in CodexRoutingHeaders. */
    override fun perTurnHeaders(meta: TurnMeta): Map<String, String> = routing.forTurn(meta)

    override fun requestRefusal(body: AnthropicTurnBody): RequestRefusal? =
        super.requestRefusal(body) ?: CodexRequestConstraints.refusal(body.typed)

    /** This path sees numeric max_tokens, not whether the caller chose it or Claude CLI supplied its default.
     *  It does not enforce a generation ceiling. Reported usage and deadlines are not token-cap enforcement. */
    override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn =
        codeModeTurns.prepare(body, sessionId, super.buildTurn(body, compact, sessionId))

    override fun cancelTurnResources() {
        codeMode.onHeadStop()
    }

    /** codex-rs 14a477ea8 codex-api/src/sse/responses.rs:65-71 captures the first HTTP token. */
    override fun observeResponseHeaders(meta: TurnMeta, response: UpstreamResponse) {
        meta.upstreamHeaders.capture(CODEX_TURN_STATE_HEADER, response.header(CODEX_TURN_STATE_HEADER))
    }

    /** Keep this route's measured negotiation and warmup surface out of ordinary Responses heads. */
    protected override val webSocketPolicy: ResponsesWebSocketPolicy = ResponsesWebSocketPolicy(
        negotiationHeaders = mapOf("OpenAI-Beta" to "responses_websockets=2026-02-06"),
        additionalToolsPrefillOnly = true,
    )

    override fun extraHeaders(creds: Credentials): Map<String, String> = buildMap {
        put("Accept", "text/event-stream")
        val accountId = (creds as? Credentials.Bearer)?.accountId
        if (accountIdHeader && accountId != null) {
            put("ChatGPT-Account-ID", accountId)
        }
    }
}
