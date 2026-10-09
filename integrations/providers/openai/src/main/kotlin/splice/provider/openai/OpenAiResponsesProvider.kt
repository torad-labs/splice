// NEW: the OpenAI-platform provider — the shared openai-responses base (ResponsesProvider) with
// api-key auth (api.openai.com, OPENAI_API_KEY), no ChatGPT-Account-ID header, first-message-hash
// cache key. Proves the base is reused across THREE auth/quirk profiles (codex/xai/openai) with zero
// duplicated drive logic; this class adds ONLY its quirk profile.
package splice.provider.openai

import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.PromptCachePolicy
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesBackendQuirks
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesReasoningQuirks
import splice.upstream.ProviderTuning

public class OpenAiResponsesProvider(
    tuning: ProviderTuning,
    reasoning: ReasoningSettings,
    quirks: ResponsesQuirks = OpenAiQuirks().defaultQuirks(),
    /** Daemon log sink — forwarded to ResponsesProvider so its diagnostics reach
     *  /mgmt/logs and not stderr alone (wall kt-no-println, 2026-07-27). */
    log: LogSink = LogSink(DaemonLog::write),
) : ResponsesProvider(tuning, reasoning, quirks, log = log)

/** Holder for the openai-platform quirk profile. A class rather than a static namespace so the
 *  profile is constructed by whoever needs it (the daemon overlays TOML on top of it), and so the
 *  default is still re-evaluated per OpenAiResponsesProvider construction exactly as the companion's
 *  function was. */
public class OpenAiQuirks {
    /** The openai-platform quirk profile — injectable so TOML [providers.*.quirks] is REAL. */
    public fun defaultQuirks(): ResponsesQuirks = ResponsesQuirks(
        providerTag = "openai",
        backend = ResponsesBackendQuirks(
            store = false,
            promptCache = PromptCachePolicy(key = CacheKeyStrategy.FIRST_MESSAGE_HASH),
        ),
        reasoning = ResponsesReasoningQuirks(
            supportsSummary = true,
        ),
    )
}
