// PORT-OF: splice/gateway/head/TurnDriver.kt (finishTurn) @ 86f1411 — invariants unchanged:
// terminal frames FIRST (finishStream), stats after — the usage-file rewrite and the cache log
// line must never sit between the last delta and message_stop on the wire. NOT folded into
// TurnTelemetry (HD-24): calling the L3 terminal from a class named "telemetry" would mislabel the
// one call the whole invariant hangs on. The cache-line construction itself DID move to
// TurnTelemetry.cacheLine — that is a log line, not the terminal.
package splice.gateway.head

import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.gateway.usage.UsageStore

internal class TurnFinish(
    private val clock: ElapsedClock,
    private val log: LogSink,
    private val usageStore: UsageStore,
    private val health: HeadHealthCounters,
    private val telemetry: TurnTelemetry,
) {
    suspend fun finishTurn(drive: TurnDrive, outcome: TurnOutcome) {
        val latencyMs = clock() - drive.t0
        log(telemetry.turnLine(drive.meta, drive.upstreamModel, outcome, latencyMs))
        val outcomeTag = drive.pipeline.finishStream(drive.emitter, outcome, drive.meta, latencyMs)
        drive.perf.mark(PerfKeys.FINISH)
        (outcome as? TurnOutcome.Success)?.let { s ->
            drive.perf.setCount(PerfKeys.IN_TOKENS, s.usage.inputTokens)
            drive.perf.setCount(PerfKeys.OUT_TOKENS, s.usage.outputTokens)
            drive.perf.setCount(PerfKeys.CACHED_TOKENS, s.usage.cachedTokens)
            drive.perf.timed(PerfKeys.USAGE_MS) { usageStore.appendOutputTokens(s.usage.outputTokens) }
            log(telemetry.cacheLine(drive.upstreamModel, s.usage, drive.meta.compact))
        }
        // Salvaged usage from absorbed rounds of an ultimately-FAILED turn: real billed tokens
        // that would otherwise vanish from the usage store and perf row (review-pr 2026-07-24).
        (outcome as? TurnOutcome.Failure)?.salvagedUsage?.let { s ->
            if (s.inputTokens > 0) drive.perf.setCount(PerfKeys.IN_TOKENS, s.inputTokens)
            if (s.outputTokens > 0) {
                drive.perf.setCount(PerfKeys.OUT_TOKENS, s.outputTokens)
                drive.perf.timed(PerfKeys.USAGE_MS) { usageStore.appendOutputTokens(s.outputTokens) }
            }
        }
        // G20 (corrected, review 2026-07-19): attribution rides the outcome's provenance flag, not
        // the ErrorType — the old OVERLOADED-implies-local heuristic misfiled a passthrough
        // provider's genuine overloaded_error as local-origin. providerReported is set ONLY where a
        // translator parsed an error the upstream actually sent.
        if (outcome is TurnOutcome.Failure) {
            if (outcome.providerReported) health.provider() else health.local()
        }
        telemetry.recordPerf(drive, outcomeTag)
    }
}
