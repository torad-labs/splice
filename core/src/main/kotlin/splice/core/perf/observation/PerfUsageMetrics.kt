package splice.core.perf.observation

import splice.core.perf.UsageMeasurementStamp

/** Canonical owned bill facts, emitted once at the original request terminal even when the bill arrives late. */
internal object PerfUsageMetrics {
    fun definitions(): List<PerfMetricDefinition> = listOf(
        "usage_cache_write_tokens" to "tokens",
        "usage_cache_write_1h_tokens" to "tokens",
        "usage_absorbed_rounds" to "rounds",
        "usage_cut_rounds" to "rounds",
        "usage_no_request" to "requests",
    ).plus(UsageMeasurementStamp.fields.keys.map { it to "requests" }).map { (id, unit) ->
        PerfMetricDefinition(
            id,
            "Canonical owned bill fact; absent remains unknown; absorbed rounds retain totals, never individual sizes",
            PerfMetricType.COUNTER,
            unit,
            "canonical usage row",
            "original request terminal",
            "one canonical usage observation per client request; late usage may revise an as-of snapshot",
            setOf(PerfEventKind.USAGE_RECORDED),
        )
    }
}
