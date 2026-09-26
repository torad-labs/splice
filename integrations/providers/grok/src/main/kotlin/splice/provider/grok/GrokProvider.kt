// NEW: the grok Provider — the shared openai-responses base (ResponsesProvider) with grok quirks:
// api-key OR oauth Bearer, session-id cache key (claude-grok:<session>) + a PER-TURN x-grok-conv-id
// header (Grok Build stickiness), effort ceiling high, reasoning.summary from config, compact effort
// inherited, tool_choice emitted. The reasoning-policy wiring lives in the base; this class adds ONLY
// the per-turn conv-id header and the grok quirk profile.
package splice.provider.grok

import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.upstream.ProviderTuning

public class GrokProvider(
    tuning: ProviderTuning,
    showReasoning: ReasoningDisplay,
    replayReasoning: Boolean,
    configEffort: String?,
    configSummary: String? = null,
    quirks: ResponsesQuirks = GrokQuirks().defaultQuirks(),
    /** Daemon log sink — forwarded to ResponsesProvider so its diagnostics reach
     *  /mgmt/logs and not stderr alone (wall kt-no-println, 2026-07-27). */
    log: LogSink = LogSink(DaemonLog::write),
) : ResponsesProvider(tuning, showReasoning, replayReasoning, configEffort, configSummary, quirks, log = log) {

    // Grok Build sets both the body prompt_cache_key AND x-grok-conv-id for sticky routing. The
    // header rides the PER-TURN BuiltTurn (via the base's perTurnHeaders hook) — a shared provider
    // field raced concurrent sessions into each other's affinity header (audit 2026-07-18).
    override fun perTurnHeaders(meta: TurnMeta): Map<String, String> =
        meta.sessionId?.takeIf { it.isNotEmpty() }?.let { mapOf("x-grok-conv-id" to it) } ?: emptyMap()
}
