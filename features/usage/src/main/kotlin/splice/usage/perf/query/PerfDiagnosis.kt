package splice.usage.perf.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.runtime.PerfRuntimeSource
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.query.PerfQueryJson.strings

internal object PerfDiagnosis {
    fun render(query: PerfQuery, data: QueryData, runtime: PerfRuntimeSource?): JsonObject = buildJsonObject {
        val runtimeSample = runtime?.let(::runtimeCopy)
        val durations = data.points.values.filter { PerfQueryMetrics.type(it.metric) == "histogram" }
            .groupBy { it.metric }.mapValues { (_, points) ->
                points.sumOf { it.sum } / points.sumOf { it.count }.coerceAtLeast(
                    1,
                )
            }
        put(
            "evidence",
            JsonArray(
                durations.entries.sortedByDescending { it.value }.take(MAX_RANKED).map { (metric, mean) ->
                    buildJsonObject {
                        put("metric", metric)
                        put("mean_ms", mean)
                        put("classification", "measured")
                        put("inference", "large_observed_duration_is_not_proof_of_root_cause")
                        put(
                            "next_falsifying_measurement",
                            "compare_same_cohort_stage_distribution_and_runtime_over_same_interval",
                        )
                        put("from_ms", query.fromMs)
                        put("until_ms", query.untilMs)
                    }
                },
            ),
        )
        put("timeline", data.timeline)
        put("runtime", runtimeSample ?: JsonNull)
        put("resources", PerfResourceDiagnosis.render(query, runtimeSample))
        put(
            "profile_interval",
            buildJsonObject {
                put("from_ms", query.fromMs)
                put("until_ms", query.untilMs)
                put("status", "suggested_comparison_interval_no_capture_evidence")
            },
        )
        put("limitations", strings(listOf("nested_attempt_and_tool_durations_are_not_additive_request_elapsed")))
    }

    private fun runtimeCopy(source: PerfRuntimeSource): JsonObject? {
        val runtime = source.runtime()
        return try {
            JsonScalars.objectOrNull(Json, runtime.toString())
        } finally {
            PerfWorkBudget.release(runtime)
        }
    }
}

// Return the twelve largest measured contributors so diagnosis remains a bounded ranked view.
private const val MAX_RANKED = 12
