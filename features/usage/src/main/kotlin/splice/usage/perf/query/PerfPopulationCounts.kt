package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.usage.perf.query.PerfQueryJson.numeric

internal object PerfPopulationCounts {
    fun aggregate(data: QueryData): JsonObject = buildJsonObject {
        put("record_unit", "request_lifecycle")
        put("filter_attribution", "event_time_scope")
        put("population_complete", data.complete)
        fields.forEach { (field, metric) ->
            val points = data.points.values.filter { it.metric == metric }
            val count = points.takeIf { it.isNotEmpty() }?.sumOf { it.sum }?.toLong()
            put(field, numeric(count ?: 0L.takeIf { data.complete }))
        }
    }

    private val fields = mapOf(
        "arrivals" to "requests_arrived",
        "completions" to "requests_finished",
        "successful_completions" to "requests_good",
        "attempts_started" to "attempts_started",
    )
}
