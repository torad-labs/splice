// NEW: the codex Provider — the shared openai-responses base (ResponsesProvider) with codex quirks
// (chatgpt-oauth, account_id header, first-message-hash cache key, max effort ceiling, summary
// supported, spark drops summary). The reasoning-policy wiring lives in the base; this class adds
// ONLY the ChatGPT-Account-ID header and the codex quirk profile.
package splice.provider.codex

import splice.core.auth.Credentials
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.spi.ProviderTuning

public class CodexProvider(
    tuning: ProviderTuning,
    showReasoning: ReasoningDisplay,
    replayReasoning: Boolean,
    configEffort: String?,
    configSummary: String?,
    quirks: ResponsesQuirks = defaultQuirks(),
    private val accountIdHeader: Boolean = true,
) : ResponsesProvider(tuning, showReasoning, replayReasoning, configEffort, configSummary, quirks) {

    override fun extraHeaders(creds: Credentials): Map<String, String> = buildMap {
        put("Accept", "text/event-stream")
        val accountId = (creds as? Credentials.Bearer)?.accountId
        if (accountIdHeader && accountId != null) {
            put("ChatGPT-Account-ID", accountId)
        }
    }

    public companion object {
        /** The codex quirk profile — injectable so the TOML [providers.*.quirks] table is REAL. */
        public fun defaultQuirks(): ResponsesQuirks = ResponsesQuirks(providerTag = "claudex")
    }
}
