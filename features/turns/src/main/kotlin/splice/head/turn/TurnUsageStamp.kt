// NEW: success/salvaged usage stamping, split from TurnFinish (concentration, 2026-08-19)
// so the finish file can drop splice.head.usage. Same-package.
package splice.head.turn

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.model.TurnBill
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.LogSink
import splice.head.usage.UsageStore

internal class TurnUsageStamp(
    private val usageStore: UsageStore,
    private val log: LogSink,
    private val telemetry: TurnTelemetry,
) {
    /** Raw completion can follow the last HTTP response. Its receipt prevents later client billing twice. */
    suspend fun stampIndependent(outcome: TurnOutcome): TurnOutcome = withContext(NonCancellable) {
        val recorded = when (outcome) {
            is TurnOutcome.Success -> outcome.copy(usage = record(outcome.usage))
            is TurnOutcome.Failure -> outcome.copy(
                partial = outcome.partial?.let { it.copy(usage = record(it.usage)) },
                salvagedUsage = record(outcome.salvagedUsage),
            )
            is TurnOutcome.ClientAbandoned -> outcome.copy(salvagedUsage = record(outcome.salvagedUsage))
        }
        usageStore.flushNow()
        recorded
    }

    private fun record(usage: Usage): Usage {
        usageStore.appendOutputTokens(usage.unrecordedOutputTokens)
        return usage.copy(recordedOutputTokens = usage.outputTokens)
    }

    suspend fun stampSuccess(drive: TurnDrive, success: TurnOutcome.Success) = withContext(NonCancellable) {
        if (!drive.claimUsageStamp()) return@withContext
        // The posting drive owns its raw rounds, even when a durable script claim returns a local step.
        val usage = drive.rawRoundUsage()?.copy(cutRounds = success.usage.cutRounds) ?: success.usage
        setKnownCounters(drive, usage)
        drive.perf.timed(PerfKeys.USAGE_MS) { usageStore.appendOutputTokens(usage.unrecordedOutputTokens) }
        log(telemetry.cacheLine(drive.upstreamModel, usage, drive.meta.compact))
    }

    suspend fun stampSalvaged(drive: TurnDrive, salvaged: Usage) = withContext(NonCancellable) {
        // Salvaged usage from absorbed rounds of an ultimately-FAILED turn — or (DR-125) an
        // abandoned one: real billed tokens that would otherwise vanish from the usage store
        // and perf row (review-pr 2026-07-24).
        if (!drive.claimUsageStamp()) return@withContext
        setKnownCounters(drive, salvaged)
        if (salvaged.unrecordedOutputTokens > 0) {
            drive.perf.timed(PerfKeys.USAGE_MS) { usageStore.appendOutputTokens(salvaged.unrecordedOutputTokens) }
        }
    }

    /** A cancellation has no aggregate outcome. Stamp the returned raw-post prefix and nothing from
     *  the interrupted post; [claimUsageStamp] makes repeated stream/collect/disconnect cleanup safe. */
    suspend fun stampKnownOnCancellation(drive: TurnDrive) = withContext(NonCancellable) {
        val known = drive.rawRoundUsage() ?: return@withContext
        if (!drive.claimUsageStamp()) return@withContext
        setKnownCounters(drive, known)
        if (known.unrecordedOutputTokens > 0) {
            drive.perf.timed(PerfKeys.USAGE_MS) { usageStore.appendOutputTokens(known.unrecordedOutputTokens) }
        }
    }

    private fun setKnownCounters(drive: TurnDrive, usage: Usage) {
        // V4-85: the cache-WRITE half of inputTokens is set UNCONDITIONALLY, like input, output and the
        // read: a dialect whose wire reports no cache-creation bucket (ChatUsage) stamps a literal 0
        // rather than an absent key, so a zero in the row means "this head wrote no cache" and an
        // absent key means "this row predates the counter". The absorbed rounds' counters appear only
        // when the turn absorbed one. TurnBill owns the mapping the pricers read back.
        TurnBill.counters(usage).forEach { (key, value) -> drive.perf.setCount(key, value) }
    }
}
