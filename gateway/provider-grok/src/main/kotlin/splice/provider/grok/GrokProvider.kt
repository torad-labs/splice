// NEW: the grok Provider — the shared openai-responses base (ResponsesProvider) with grok quirks:
// api-key OR oauth Bearer, session-id cache key (claude-grok:<session>) + a PER-TURN x-grok-conv-id
// header (Grok Build stickiness), effort ceiling high, reasoning.summary from config, compact effort
// inherited, tool_choice emitted. The reasoning-policy wiring lives in the base; this class adds ONLY
// the per-turn conv-id header and the grok quirk profile.
package splice.provider.grok

import splice.core.auth.Credentials
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.EffortLadder
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.spi.ProviderTuning

public class GrokProvider(
    tuning: ProviderTuning,
    showReasoning: ReasoningDisplay,
    replayReasoning: Boolean,
    configEffort: String?,
    configSummary: String? = null,
    quirks: ResponsesQuirks = defaultQuirks(),
) : ResponsesProvider(tuning, showReasoning, replayReasoning, configEffort, configSummary, quirks) {

    // Grok Build sets both the body prompt_cache_key AND x-grok-conv-id for sticky routing. The
    // header rides the PER-TURN BuiltTurn (via the base's perTurnHeaders hook) — a shared provider
    // field raced concurrent sessions into each other's affinity header (audit 2026-07-18).
    override fun perTurnHeaders(sessionId: String?): Map<String, String> =
        sessionId?.takeIf { it.isNotEmpty() }?.let { mapOf("x-grok-conv-id" to it) } ?: emptyMap()

    override fun extraHeaders(creds: Credentials): Map<String, String> = mapOf("Accept" to "text/event-stream")

    public companion object {
        /** The grok quirk profile — injectable so the TOML [providers.*.quirks] table is REAL. */
        public fun defaultQuirks(): ResponsesQuirks = ResponsesQuirks(
            providerTag = "claude-grok",
            store = false,
            cacheKeyStrategy = CacheKeyStrategy.SESSION_ID,
            effortLadder = EffortLadder.GROK,
            supportsSummary = true,
            summaryRejectModelRegex = null,
            compactEffortPin = null, // inherit session effort on compact (v27 cache law — no pins)
            emitToolChoice = true,
            emitStrict = true,
            // xai returns no encrypted reasoning envelopes, so the cache would only widen the
            // request's include[] for nothing (untested surface on xai). TOML
            // `reasoning_cache = true` re-enables via the overlay if that ever changes.
            reasoningCache = false,
        )
    }
}
