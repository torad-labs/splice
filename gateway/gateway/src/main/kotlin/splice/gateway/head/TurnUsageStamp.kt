// NEW: success/salvaged usage stamping, split from TurnFinish (concentration, 2026-08-19)
// so the finish file can drop splice.gateway.usage. Same-package.
package splice.gateway.head

import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.gateway.usage.UsageStore

internal class TurnUsageStamp(
    private val usageStore: UsageStore,
    private val log: LogSink,
    private val telemetry: TurnTelemetry,
) {
    suspend fun stampSuccess(drive: TurnDrive, success: TurnOutcome.Success) {
        drive.perf.setCount(PerfKeys.IN_TOKENS, success.usage.inputTokens)
        drive.perf.setCount(PerfKeys.OUT_TOKENS, success.usage.outputTokens)
        drive.perf.setCount(PerfKeys.CACHED_TOKENS, success.usage.cachedTokens)
        drive.perf.timed(PerfKeys.USAGE_MS) { usageStore.appendOutputTokens(success.usage.outputTokens) }
        log(telemetry.cacheLine(drive.upstreamModel, success.usage, drive.meta.compact))
    }

    suspend fun stampSalvaged(drive: TurnDrive, outcome: TurnOutcome.Failure) {
        // Salvaged usage from absorbed rounds of an ultimately-FAILED turn: real billed tokens
        // that would otherwise vanish from the usage store and perf row (review-pr 2026-07-24).
        outcome.salvagedUsage?.let { s ->
            if (s.inputTokens > 0) drive.perf.setCount(PerfKeys.IN_TOKENS, s.inputTokens)
            if (s.outputTokens > 0) {
                drive.perf.setCount(PerfKeys.OUT_TOKENS, s.outputTokens)
                drive.perf.timed(PerfKeys.USAGE_MS) { usageStore.appendOutputTokens(s.outputTokens) }
            }
        }
    }
}
