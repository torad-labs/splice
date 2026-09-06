// NEW: success/salvaged usage stamping, split from TurnFinish (concentration, 2026-08-19)
// so the finish file can drop splice.gateway.usage. Same-package.
package splice.gateway.head

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.LogSink
import splice.gateway.usage.UsageStore

internal class TurnUsageStamp(
    private val usageStore: UsageStore,
    private val log: LogSink,
    private val telemetry: TurnTelemetry,
) {
    suspend fun stampSuccess(drive: TurnDrive, success: TurnOutcome.Success) = withContext(NonCancellable) {
        if (!drive.claimUsageStamp()) return@withContext
        setKnownCounters(drive, success.usage)
        drive.perf.timed(PerfKeys.USAGE_MS) { usageStore.appendOutputTokens(success.usage.outputTokens) }
        log(telemetry.cacheLine(drive.upstreamModel, success.usage, drive.meta.compact))
    }

    suspend fun stampSalvaged(drive: TurnDrive, salvaged: Usage) = withContext(NonCancellable) {
        // Salvaged usage from absorbed rounds of an ultimately-FAILED turn — or (DR-125) an
        // abandoned one: real billed tokens that would otherwise vanish from the usage store
        // and perf row (review-pr 2026-07-24).
        if (!drive.claimUsageStamp()) return@withContext
        setKnownCounters(drive, salvaged)
        if (salvaged.outputTokens > 0) {
            drive.perf.timed(PerfKeys.USAGE_MS) { usageStore.appendOutputTokens(salvaged.outputTokens) }
        }
    }

    /** A cancellation has no aggregate outcome. Stamp the returned raw-post prefix and nothing from
     *  the interrupted post; [claimUsageStamp] makes repeated stream/collect/disconnect cleanup safe. */
    suspend fun stampKnownOnCancellation(drive: TurnDrive) = withContext(NonCancellable) {
        val known = drive.rawRoundUsage() ?: return@withContext
        if (!drive.claimUsageStamp()) return@withContext
        setKnownCounters(drive, known)
        if (known.outputTokens > 0) {
            drive.perf.timed(PerfKeys.USAGE_MS) { usageStore.appendOutputTokens(known.outputTokens) }
        }
    }

    private fun setKnownCounters(drive: TurnDrive, usage: Usage) {
        drive.perf.setCount(PerfKeys.IN_TOKENS, usage.inputTokens)
        drive.perf.setCount(PerfKeys.OUT_TOKENS, usage.outputTokens)
        drive.perf.setCount(PerfKeys.CACHED_TOKENS, usage.cachedTokens)
    }
}
