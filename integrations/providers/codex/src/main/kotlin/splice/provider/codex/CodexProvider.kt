// NEW: the codex Provider — the shared openai-responses base (ResponsesProvider) with codex quirks
// (chatgpt-oauth, account_id header, first-message-hash cache key, max effort ceiling, summary
// supported, spark drops summary). The reasoning-policy wiring lives in the base; this class adds
// ONLY the ChatGPT-Account-ID header, codex-rs's per-turn routing/session headers
// (CodexRoutingHeaders) and the codex quirk profile.
package splice.provider.codex

import splice.core.auth.Credentials
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.request.ResponsesToolResultMedia
import splice.dialect.responses.stream.FoldConfig
import splice.dialect.responses.websocket.CODEX_TURN_STATE_HEADER
import splice.upstream.BuiltTurn
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamResponse

public class CodexProvider(
    tuning: ProviderTuning,
    showReasoning: ReasoningDisplay,
    replayReasoning: Boolean,
    configEffort: String?,
    configSummary: String?,
    quirks: ResponsesQuirks = CodexQuirks().defaultQuirks(),
    // Reasoning-continuation folding (codex 518n-2). null = off; the daemon wires it from config.
    foldConfig: FoldConfig? = null,
    private val accountIdHeader: Boolean = true,
    /** Daemon log sink — forwarded to ResponsesProvider so its diagnostics reach
     *  /mgmt/logs and not stderr alone (wall kt-no-println, 2026-07-27). */
    log: LogSink = LogSink(DaemonLog::write),
    codeModeBridge: CodexCodeModeBridge? = null,
    /** Upstream model ids the operator adds to those the backend marks code-mode-only; null adds none. */
    codeModeModels: Collection<String>? = null,
    /** The models the backend marks `code_mode_only`, asked each turn (V4-441). */
    codeModeOnly: CodeModeOnlyModels = NoCodeModeOnlyModels,
) : ResponsesProvider(tuning, showReasoning, replayReasoning, configEffort, configSummary, quirks, foldConfig, log) {

    private val codeModeTurns = CodexCodeModeTurnBuilder(
        codeModeBridge,
        ResponsesToolResultMedia(quirks),
        codeModeModels,
        codeModeOnly,
    )
    private val codeMode = codeModeBridge

    /** Proven against the live ChatGPT backend by the WS-0 spike
     *  (.dev/research/spikes/responses-websocket.md): handshake, event vocabulary and
     *  previous_response_id chaining all confirmed. No other Responses upstream has been probed. */
    override val supportsWebSocket: Boolean = true

    private val routing = CodexRoutingHeaders()

    /** codex-rs's per-turn routing/session headers — the measurement is in CodexRoutingHeaders. */
    override fun perTurnHeaders(meta: TurnMeta): Map<String, String> = routing.forTurn(meta)

    override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn =
        codeModeTurns.prepare(body, compact, sessionId, super.buildTurn(body, compact, sessionId))

    override fun onHeadStop() {
        codeMode?.onHeadStop()
    }

    /** codex-rs 14a477ea8 codex-api/src/sse/responses.rs:65-71 captures the first HTTP token. */
    override fun observeResponseHeaders(meta: TurnMeta, response: UpstreamResponse) {
        meta.upstreamHeaders.capture(CODEX_TURN_STATE_HEADER, response.header(CODEX_TURN_STATE_HEADER))
    }

    override fun extraHeaders(creds: Credentials): Map<String, String> = buildMap {
        put("Accept", "text/event-stream")
        val accountId = (creds as? Credentials.Bearer)?.accountId
        if (accountIdHeader && accountId != null) {
            put("ChatGPT-Account-ID", accountId)
        }
    }
}

/**
 * The models the backend runs on the code-mode surface alone, asked at TURN time and never captured (V4-441):
 * the daemon's roster is refreshed while it runs, and a set held from build would hide the refresh. Ids are
 * whatever the backend published (`tool_mode = "code_mode_only"`); [CodexCodeModeModels.eligible] normalizes.
 */
public fun interface CodeModeOnlyModels {
    public fun ids(): Collection<String>
}

/** No backend list known: only the operator's `code_mode_models` runs code mode. */
internal object NoCodeModeOnlyModels : CodeModeOnlyModels {
    override fun ids(): Collection<String> = emptyList()
}
