package splice.usage.perf.query

import splice.core.perf.observation.PerfObservation
import splice.core.util.PerfWorkBudget
import splice.usage.perf.history.PerfEvidenceQuery

/** Streaming source consumption and scalar request reduction share the global work budget. */
internal class PerfEvidenceQueryFold(private val sources: PerfQuerySources) {
    fun read(query: PerfQuery, filter: PerfQueryFilter, nowMs: Long, data: QueryData) {
        val source = sources.evidence()
        if (source == null) {
            data.missing.add("exact_request_evidence")
            data.issue("exact_request_evidence_unavailable", "evidence")
            return
        }
        val lease = PerfWorkBudget.acquire(PERF_REDUCTION_BYTES)
        if (lease == null) {
            data.issue("request_reducer_memory_busy", "query")
            return
        }
        lease.use {
            val through = maxOf(query.untilMs, data.settledUntilMs?.let { minOf(nowMs, it) } ?: nowMs)
            val reduction = PerfRequestReduction(query, filter, data)
            source.consume(
                PerfEvidenceQuery(query.fromMs, through, query.filters["head"]),
                reduction,
            ).use(reduction::finish)
        }
    }
}

internal object PerfEvidenceWindow {
    fun inside(event: PerfObservation, query: PerfQuery): Boolean =
        event.stamp.wallMs >= query.fromMs && event.stamp.wallMs < query.untilMs
}
