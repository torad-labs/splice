package splice.head.perf

import splice.core.model.TurnBill
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSnapshot
import splice.core.perf.TurnPerf

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
        }
        request.usage(values)
    }

    private fun observed(row: Map<String, Long>, latest: String, absorbed: String): Boolean =
        latest in row || absorbed in row

    private fun missing(row: Map<String, Long>): Double {
        val local = row[PerfKeys.LOCAL_STEP] == 1L && (row[PerfKeys.TRANSPORT_ATTEMPT_STARTS] ?: 0L) == 0L
        return if (local || TurnBill.fullyReported(row)) 0.0 else 1.0
    }
}
