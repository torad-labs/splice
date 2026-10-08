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
import splice.dialect.responses.request.ResponsesToolResultMedia
import splice.dialect.responses.stream.FoldConfig
import splice.dialect.responses.websocket.CODEX_TURN_STATE_HEADER
import splice.upstream.BuiltTurn
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamResponse

public class CodexProvider(
    tuning: ProviderTuning,
    reasoning: ReasoningSettings,
    quirks: ResponsesQuirks = CodexQuirks().defaultQuirks(),
    // Reasoning-continuation folding (codex 518n-2). null = off; the daemon wires it from config.
    foldConfig: FoldConfig? = null,
    private val accountIdHeader: Boolean = true,
    /** Daemon log sink — forwarded to ResponsesProvider so its diagnostics reach
     *  /mgmt/logs and not stderr alone (wall kt-no-println, 2026-07-27). */
    log: LogSink = LogSink(DaemonLog::write),
    private val codeMode: CodexCodeModeWiring = CodexCodeModeWiring(),
) : ResponsesProvider(tuning, reasoning, quirks, foldConfig, log) {

    private val codeModeTurns = codeMode.turnBuilder(ResponsesToolResultMedia(quirks))

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
        codeMode.onHeadStop()
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

/**
 * The code-mode pieces a Codex head is wired with. [bridge] is the cell runtime (null = code mode off),
 * [models] the upstream model ids the operator adds to those the backend marks code-mode-only (null adds
 * none), and [onlyModels] the models the backend marks `code_mode_only`, asked each turn (V4-441).
 */
public class CodexCodeModeWiring(
    private val bridge: CodexCodeModeBridge? = null,
    private val models: Collection<String>? = null,
    private val onlyModels: CodeModeOnlyModels = NoCodeModeOnlyModels,
) {
    internal fun turnBuilder(media: ResponsesToolResultMedia): CodexCodeModeTurnBuilder =
        CodexCodeModeTurnBuilder(bridge, media, models, onlyModels)

    internal fun onHeadStop() {
        bridge?.onHeadStop()
    }
}
