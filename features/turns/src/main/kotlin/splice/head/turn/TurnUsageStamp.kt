// NEW: success/salvaged usage stamping, split from TurnFinish (concentration, 2026-08-19)
// so the finish file can drop splice.head.usage. Same-package.
package splice.head.turn

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.model.TurnBill
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.perf.SpeedCounters
import splice.core.perf.UsageMeasurementStamp
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.UsageRequest
import splice.head.usage.UsageStore

internal class TurnUsageStamp(
    private val usageStore: UsageStore,
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
        return usage.copy(origin = usage.origin.copy(recordedOutputTokens = usage.outputTokens))
    }

    suspend fun stampSuccess(drive: TurnDrive, success: TurnOutcome.Success) = withContext(NonCancellable) {
        drive.speed = success.usage.speed ?: drive.speed
        if (!drive.claimUsageStamp()) return@withContext
        // The posting drive owns its raw rounds, even when a durable script claim returns a local step.
        val raw = drive.rawRoundUsage()
        val usage = raw?.let {
            val history = it.origin.history.copy(cutRounds = success.usage.cutRounds)
            it.copy(origin = it.origin.copy(history = history))
        } ?: success.usage
        setKnownCounters(drive, usage)
        drive.perf.timed(PerfKeys.USAGE_MS) { usageStore.appendOutputTokens(usage.unrecordedOutputTokens) }
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
        RowBill(drive).stamp(usage)
        UsageMeasurementStamp.counters(usage).forEach { (key, value) -> drive.perf.setCount(key, value) }
    }
}

/** A posted round without a token report must not persist an invented zero in any billing bucket. */
private val UNREPORTED_TOKEN_FIELDS = setOf(
    PerfKeys.IN_TOKENS,
    PerfKeys.OUT_TOKENS,
    PerfKeys.CACHED_TOKENS,
    PerfKeys.CACHE_WRITE_TOKENS,
    PerfKeys.CACHE_WRITE_1H_TOKENS,
    PerfKeys.ABSORBED_IN_TOKENS,
    PerfKeys.ABSORBED_OUT_TOKENS,
    PerfKeys.ABSORBED_CACHED_TOKENS,
    PerfKeys.ABSORBED_CACHE_WRITE_TOKENS,
    PerfKeys.ABSORBED_CACHE_WRITE_1H_TOKENS,
)

/** The bill [drive]'s perf row carries: what a known usage puts on its counters, and the snapshot the row is written
 *  from. One author, so a usage stamped as the turn ends and one a held row is written with read the same. */
internal class RowBill(private val drive: TurnDrive) {
    /** [usage] on the row's counters. TurnBill emits only observed buckets: an explicit zero is retained, but a failed
     *  stream that reported no usage leaves the billing fields absent, not fabricated zeros, and absorbed-round
     *  counters appear only when the turn absorbed a measured request. A posted bill replaces any earlier no-request
     *  ownership. With it go the speed the request asked for and the one the backend served it at, which prices it
     *  (gpt-speed). */
    fun stamp(usage: Usage) {
        drive.perf.setCount(PerfKeys.NO_REQUEST, 0L)
        TurnBill.counters(usage).forEach { (key, value) -> drive.perf.setCount(key, value) }
        SpeedCounters.counters(drive.requestBody, usage).forEach { (key, value) -> drive.perf.setCount(key, value) }
    }

    /** The row's counters once its bill is whole. A turn an interceptor assembled that did post a request owns no
     *  local step, and where its round reported no tokens the row carries no billing bucket at all. */
    fun snapshot(): PerfSnapshot {
        val snap = drive.perf.snapshot()
        val raw = drive.rawRoundUsage()
        val started = (snap.counters[PerfKeys.TRANSPORT_ATTEMPT_STARTS] ?: 0L) > 0L
        val posted = raw?.origin?.history?.request?.let { it == UsageRequest.POSTED } ?: started
        if (drive.roundInterceptor == null || !posted) return snap
        val counters = snap.counters - PerfKeys.LOCAL_STEP - PerfKeys.NO_REQUEST
        val observed = raw?.reported.orEmpty().isNotEmpty()
        return if (TurnBill.isEmpty(counters) && !observed) {
            snap.copy(counters = counters.filterKeys { it !in UNREPORTED_TOKEN_FIELDS })
        } else {
            snap.copy(counters = counters)
        }
    }
}
