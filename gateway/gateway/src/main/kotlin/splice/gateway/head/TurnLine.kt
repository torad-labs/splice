// NEW: the per-turn outcome log line, split from TurnTelemetry (concentration, 2026-08-19)
// so the observability file is not billed for this render. Same-package.
package splice.gateway.head

import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.spi.WatchdogFired

internal class TurnLine(
    private val headKey: String,
) {
    // Per-turn telemetry: outcome + latency (+ tokens/type). The compaction-stall and API-error
    // signals live here — a compact turn that FAILUREs or runs many seconds is now visible.
    fun render(
        meta: TurnMeta,
        model: String,
        outcome: TurnOutcome,
        latencyMs: Long,
        fired: WatchdogFired? = null,
    ): String {
        val base = "[$headKey] turn compact=${meta.compact} model=$model latency=${latencyMs}ms"
        return base + verdict(fired) + when (outcome) {
            is TurnOutcome.Success ->
                " ok out=${outcome.usage.outputTokens} tool=${outcome.hasToolUse} incomplete=${outcome.incomplete}\n"
            is TurnOutcome.Failure ->
                " FAILURE type=${outcome.type.wireName} msg=${outcome.message.take(ERR_SNIPPET)}\n"
            is TurnOutcome.ClientAbandoned -> " client-abandoned\n"
        }
    }

    /** The watchdog's own verdict, in the numbers it actually judged on.
     *
     *  Why this is not derivable from the message (live, 2026-09-02): five compactions died saying
     *  "no completion within the 180s idle cap", which the terminal renders whenever the fired
     *  sentinel says a client frame was seen — and it prints the CONFIGURED streamIdle, never the
     *  limit that fired. Their perf rows carried no first_frame and no content_frames_out at all,
     *  i.e. the round's client-frame probe should have read false and put them on the first-output
     *  tier, a tier a compact turn does not have (WatchdogBudget.forCompact switches it off), so
     *  only the whole-turn cap could have ended them. Message and counters disagreed and nothing in
     *  the log could break the tie, because the one number that would — the limit the poller
     *  compared against — was never written down. The production-path test for exactly this case
     *  passes, so the harness does not reproduce whatever the live path does; the next occurrence
     *  has to answer for itself. Absent a fire this adds nothing to the line. */
    private fun verdict(fired: WatchdogFired?): String = when (fired) {
        null -> ""
        is WatchdogFired.Idle -> {
            val tier = if (fired.sawClientFrame) "mid-output" else "first-output"
            " watchdog=idle(tier=$tier limit=${fired.limitMs}ms idle=${fired.idleMs}ms)"
        }
        is WatchdogFired.TotalCap -> " watchdog=total-cap(elapsed=${fired.elapsedMs}ms)"
    }
}
