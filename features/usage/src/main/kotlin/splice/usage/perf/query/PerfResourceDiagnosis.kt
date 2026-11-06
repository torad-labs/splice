package splice.usage.perf.query

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfMetricRegistry
import splice.core.perf.observation.PerfProjection
import splice.core.util.JsonScalars
import splice.usage.perf.query.PerfQueryJson.numeric

/** Capacity fractions rank measured occupancy, without inventing a universal pressure threshold. */
internal object PerfResourceDiagnosis {
    fun render(query: PerfQuery, runtime: JsonObject?): JsonObject = buildJsonObject {
        val sampledAt = runtime?.let { JsonScalars.str(it, "as_of_ms")?.toLongOrNull()?.takeIf { it > 0 } }
        val relevant = sampledAt != null && sampledAt >= query.fromMs && sampledAt < query.untilMs
        val values = measurements(runtime)
        put("status", if (relevant) "sample_in_requested_window" else "unavailable_for_requested_window")
        put("sample_at_ms", numeric(sampledAt))
        put("scope", "process_sample_not_request_cpu_ownership")
        put("interval_coverage", "single_sample_does_not_establish_whole_window_coverage")
        put("ranking_basis", "fraction_of_reported_capacity_descending_not_causal_priority")
        put("unranked_reason", "remaining_measurements_lack_a_reported_capacity_or_comparable_normalization")
        put("conditions", if (relevant) conditions(values) else JsonArray(emptyList()))
        put(
            "measurements",
            if (relevant) JsonObject(values.mapValues { JsonPrimitive(it.value) }) else JsonObject(emptyMap()),
        )
        put("reason", if (relevant) "measured_at_sample_time_only" else "current_runtime_is_not_historical_evidence")
        put(
            "next_falsifying_measurement",
            "collect_runtime_samples_during_the_same_request_interval_and_compare_baseline",
        )
    }

    private fun measurements(runtime: JsonObject?): Map<String, Double> {
        val measurements = runtime?.get("measurements") as? JsonObject ?: JsonObject(emptyMap())
        return measurements.mapNotNull { (metric, value) ->
            JsonScalars.strOrEmpty(value).toDoubleOrNull()?.takeIf { accepted(metric, it) }?.let { metric to it }
        }.toMap()
    }

    private fun accepted(metric: String, value: Double): Boolean =
        metric.startsWith("runtime_") && PerfMetricRegistry.permits(metric, PerfProjection.LOCAL) &&
            value.isFinite() && value >= 0

    private fun conditions(values: Map<String, Double>): JsonArray {
        val ratios = capacityPairs.mapNotNull { (used, capacity) ->
            val numerator = values[used] ?: return@mapNotNull null
            val denominator = values[capacity]?.takeIf { it > 0 } ?: return@mapNotNull null
            ResourceOccupancy(used, numerator, capacity, denominator).takeIf { it.ratio.isFinite() }
        }.toMutableList()
        values["runtime_cpu_ratio"]?.let { ratios.add(ResourceOccupancy("runtime_cpu_ratio", it, null, 1.0)) }
        return JsonArray(
            ratios.sortedByDescending { it.ratio }.mapIndexed { index, value ->
                buildJsonObject {
                    put("rank", index + 1)
                    put("metric", value.metric)
                    put("value", value.value)
                    put("capacity_metric", PerfQueryJson.textValue(value.capacityMetric))
                    put("capacity", value.capacity)
                    put("fraction", value.ratio)
                    put("classification", "measured_occupancy")
                    put("inference", "occupancy_is_not_proof_of_saturation_or_request_delay")
                }
            },
        )
    }

    private val capacityPairs = mapOf(
        "runtime_heap_used_bytes" to "runtime_heap_max_bytes",
        "runtime_fd_count" to "runtime_fd_limit",
        "runtime_tools_active" to "runtime_tool_capacity",
    )
}

private data class ResourceOccupancy(
    val metric: String,
    val value: Double,
    val capacityMetric: String?,
    val capacity: Double,
) {
    val ratio: Double get() = value / capacity
}
