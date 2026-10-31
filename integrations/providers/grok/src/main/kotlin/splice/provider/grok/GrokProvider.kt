// NEW: the grok Provider — the shared openai-responses base (ResponsesProvider) with grok quirks:
// api-key OR oauth Bearer, session-id cache key (claude-grok:<session>) + a PER-TURN x-grok-conv-id
// header (Grok Build stickiness), effort ceiling high, reasoning.summary from config, compact effort
// inherited, tool_choice emitted. The reasoning-policy wiring lives in the base; this class adds ONLY
// the per-turn conv-id header and the grok quirk profile.
package splice.provider.grok

import splice.core.turn.TurnMeta
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.failure.ResponsesReplies
import splice.upstream.ProviderTuning
import splice.upstream.failure.Replies

public class GrokProvider(
    tuning: ProviderTuning,
    reasoning: ReasoningSettings,
    quirks: ResponsesQuirks = GrokQuirks().defaultQuirks(),
    /** Daemon log sink — forwarded to ResponsesProvider so its diagnostics reach
     *  /mgmt/logs and not stderr alone (wall kt-no-println, 2026-07-27). */
    log: LogSink = LogSink(DaemonLog::write),
    /** How this head reads its own failures: xAI's own answer about a dead token before the Responses standard,
     *  which reads its spending limit and its refused images (failure-handling slice 3). */
    replies: Replies = Replies(ResponsesReplies, GrokReplies),
) : ResponsesProvider(tuning, reasoning, quirks, log = log, replies = replies) {

    // Grok Build sets both the body prompt_cache_key AND x-grok-conv-id for sticky routing. The
    // header rides the PER-TURN BuiltTurn (via the base's perTurnHeaders hook) — a shared provider
    // field raced concurrent sessions into each other's affinity header (audit 2026-07-18).
    override fun perTurnHeaders(meta: TurnMeta): Map<String, String> =
        meta.scope.sessionId?.takeIf { it.isNotEmpty() }?.let { mapOf("x-grok-conv-id" to it) } ?: emptyMap()
}
