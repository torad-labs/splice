// NEW: the codex Provider — the shared openai-responses base (ResponsesProvider) with codex quirks
// (chatgpt-oauth, account_id header, first-message-hash cache key, max effort ceiling, summary
// supported, spark drops summary). The reasoning-policy wiring lives in the base; this class adds
// ONLY the ChatGPT-Account-ID header and the codex quirk profile.
package splice.provider.codex

import splice.core.auth.Credentials
import splice.core.turn.ReasoningDisplay
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.dialect.responses.FoldConfig
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.spi.ProviderTuning

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
) : ResponsesProvider(tuning, showReasoning, replayReasoning, configEffort, configSummary, quirks, foldConfig, log) {

    /** Proven against the live ChatGPT backend by the WS-0 spike
     *  (gateway/spikes/results/responses-websocket.md): handshake, event vocabulary and
     *  previous_response_id chaining all confirmed. No other Responses upstream has been probed. */
    override val supportsWebSocket: Boolean = true

    override fun extraHeaders(creds: Credentials): Map<String, String> = buildMap {
        put("Accept", "text/event-stream")
        val accountId = (creds as? Credentials.Bearer)?.accountId
        if (accountIdHeader && accountId != null) {
            put("ChatGPT-Account-ID", accountId)
        }
    }
}
