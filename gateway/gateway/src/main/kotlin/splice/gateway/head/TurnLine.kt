// NEW: the per-turn outcome log line, split from TurnTelemetry (concentration, 2026-08-19)
// so the observability file is not billed for this render. Same-package.
package splice.gateway.head

import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome

internal class TurnLine(
    private val headKey: String,
) {
    // Per-turn telemetry: outcome + latency (+ tokens/type). The compaction-stall and API-error
    // signals live here — a compact turn that FAILUREs or runs many seconds is now visible.
    fun render(meta: TurnMeta, model: String, outcome: TurnOutcome, latencyMs: Long): String {
        val base = "[$headKey] turn compact=${meta.compact} model=$model latency=${latencyMs}ms"
        return base + when (outcome) {
            is TurnOutcome.Success ->
                " ok out=${outcome.usage.outputTokens} tool=${outcome.hasToolUse} incomplete=${outcome.incomplete}\n"
            is TurnOutcome.Failure ->
                " FAILURE type=${outcome.type.wireName} msg=${outcome.message.take(ERR_SNIPPET)}\n"
            TurnOutcome.ClientAbandoned -> " client-abandoned\n"
        }
    }
}
