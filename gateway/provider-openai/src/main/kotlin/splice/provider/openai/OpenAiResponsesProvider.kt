// NEW: the OpenAI-platform provider — the shared openai-responses base (ResponsesProvider) with
// api-key auth (api.openai.com, OPENAI_API_KEY), no ChatGPT-Account-ID header, first-message-hash
// cache key. Proves the base is reused across THREE auth/quirk profiles (codex/xai/openai) with zero
// duplicated drive logic; this class adds ONLY its quirk profile.
package splice.provider.openai

import splice.core.auth.Credentials
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.EffortLadder
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.spi.ProviderTuning

public class OpenAiResponsesProvider(
    tuning: ProviderTuning,
    showReasoning: String,
    replayReasoning: Boolean,
    configEffort: String?,
    configSummary: String?,
    quirks: ResponsesQuirks = defaultQuirks(),
) : ResponsesProvider(tuning, showReasoning, replayReasoning, configEffort, configSummary, quirks) {

    // api key rides as the standard bearer (UpstreamClient maps Credentials.ApiKey); no account header.
    override fun extraHeaders(creds: Credentials): Map<String, String> = mapOf("Accept" to "text/event-stream")

    public companion object {
        /** The openai-platform quirk profile — injectable so TOML [providers.*.quirks] is REAL. */
        public fun defaultQuirks(): ResponsesQuirks = ResponsesQuirks(
            providerTag = "openai",
            store = false,
            cacheKeyStrategy = CacheKeyStrategy.FIRST_MESSAGE_HASH,
            effortLadder = EffortLadder.CODEX,
            supportsSummary = true,
        )
    }
}
