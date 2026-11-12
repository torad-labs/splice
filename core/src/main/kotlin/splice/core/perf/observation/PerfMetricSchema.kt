package splice.core.perf.observation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PerfMetricSchema {
    fun render(metric: PerfMetricDefinition): JsonObject = buildJsonObject {
        put("description", metric.description)
        put("type", metric.type.name.lowercase())
        put("unit", metric.unit)
        put("origin", metric.origin)
        put("end", metric.end)
        put("population", metric.population)
        put("aggregation", aggregation(metric.type))
        put("path_support", JsonArray(metric.paths.map(::JsonPrimitive)))
        put("missing_reasons", JsonArray(metric.missingReasons.map(::JsonPrimitive)))
        put("cardinality", "256 scopes per family; overflow explicit; no request, session, project or account labels")
        put("projections", JsonArray(metric.projections.map { JsonPrimitive(it.name) }))
        put("events", JsonArray(metric.events.map { JsonPrimitive(it.name) }))
    }

    private fun aggregation(type: PerfMetricType): String = when (type) {
        PerfMetricType.COUNTER -> "sum event deltas by minute"
        PerfMetricType.HISTOGRAM -> "merge count, sum, min, max and disjoint buckets; p50/p95/p99 from merged counts"
        PerfMetricType.GAUGE -> "last and monotonic time-weighted mean; never sum repeated cumulative samples"
        PerfMetricType.LEGACY -> "legacy row only; missing stays absent; never mapped to a different clock origin"
    }
}
