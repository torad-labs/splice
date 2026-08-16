// NEW: the codex Provider — the shared openai-responses base (ResponsesProvider) with codex quirks
// (chatgpt-oauth, account_id header, first-message-hash cache key, max effort ceiling, summary
// supported, spark drops summary). The reasoning-policy wiring lives in the base; this class adds
// ONLY the ChatGPT-Account-ID header and the codex quirk profile.
package splice.provider.codex

import splice.core.auth.Credentials
import splice.core.turn.ReasoningDisplay
import splice.core.util.DaemonLog
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
    log: (String) -> Unit = DaemonLog::write,
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

/** Holder for the codex quirk profile. A class rather than a static namespace so the profile is
 *  constructed by whoever needs it (the daemon overlays TOML on top of it), and so the default is
 *  still re-evaluated per CodexProvider construction exactly as the companion's function was. */
public class CodexQuirks {
    /** The codex quirk profile — injectable so the TOML [providers.*.quirks] table is REAL. */
    public fun defaultQuirks(): ResponsesQuirks = ResponsesQuirks(
        providerTag = "claudex",
        // richer titled reasoning sections from the ChatGPT backend (probed 2026-07-19)
        summaryDelivery = "sequential_cutoff",
        // codex-rs parity: hard-sets strict:false on every function tool (responses_api.rs:29-32);
        // OpenCode does the same, marked "Codex parity". Omitting it lets the backend attempt
        // strict auto-normalisation of ~87 MCP schemas and silently report whatever it settled on.
        // forceStrictFalse, NOT emitStrict (review 2026-07-24): emitStrict is grok's pre-existing,
        // never-consequential pass-through flag — reusing it here silently changed grok's bytes too.
        forceStrictFalse = true,
    )
}
