package splice.head.perf

import splice.core.model.TurnBill
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.perf.TurnPerf
import splice.core.perf.UsageMeasurementStamp
import splice.core.turn.UsageRequest

/** The canonical row owns the usage observation, including its held source's final bill. */
internal object UsageObservation {
    fun record(perf: TurnPerf, snapshot: PerfSnapshot) {
        val request = perf.observations ?: return
        val row = snapshot.counters
        val total = TurnBill.total(row)
        val values = buildMap {
            if (observed(row, PerfKeys.IN_TOKENS, PerfKeys.ABSORBED_IN_TOKENS)) {
                put("input_tokens", total.requestInput.toDouble())
            }
            if (observed(row, PerfKeys.OUT_TOKENS, PerfKeys.ABSORBED_OUT_TOKENS)) {
                put("output_tokens", total.output.toDouble())
            }
            if (observed(row, PerfKeys.CACHED_TOKENS, PerfKeys.ABSORBED_CACHED_TOKENS)) {
                put("cached_tokens", total.cacheRead.toDouble())
            }
            put("missing_usage", missing(row))
            putAll(comparisonFacts(row))
        }
        request.usage(values)
    }

    private fun comparisonFacts(row: Map<String, Long>): Map<String, Double> = buildMap {
        val total = TurnBill.total(row)
        UsageMeasurementStamp.fields.keys.forEach { put(it, if (row[it] == 1L) 1.0 else 0.0) }
        if (row[PerfKeys.USAGE_OBSERVED_CACHE_WRITE] == 1L) {
            put("usage_cache_write_tokens", total.cacheWrite.toDouble())
        }
        if (row[PerfKeys.USAGE_OBSERVED_CACHE_WRITE_1H] == 1L) {
            put("usage_cache_write_1h_tokens", total.cacheWriteHourly.toDouble())
        }
        val history = TurnBill.history(row)
        put("usage_absorbed_rounds", history.absorbed.rounds.toDouble())
        put("usage_cut_rounds", history.cutRounds.toDouble())
        put("usage_no_request", if (history.request == UsageRequest.NONE) 1.0 else 0.0)
    }

    private fun observed(row: Map<String, Long>, latest: String, absorbed: String): Boolean =
        latest in row || absorbed in row

    private fun missing(row: Map<String, Long>): Double {
        val local = row[PerfKeys.LOCAL_STEP] == 1L && (row[PerfKeys.TRANSPORT_ATTEMPT_STARTS] ?: 0L) == 0L
        return if (local || TurnBill.fullyReported(row)) 0.0 else 1.0
    }
}
