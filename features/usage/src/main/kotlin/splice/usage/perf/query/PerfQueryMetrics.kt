package splice.usage.perf.query

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfMetricRegistry
import splice.core.util.JsonScalars
import splice.usage.perf.query.PerfQueryJson.coverageJson
import splice.usage.perf.query.PerfQueryJson.numeric
import splice.usage.perf.query.PerfQueryJson.scopeJson
import kotlin.math.ceil

internal object PerfQueryMetrics {
    private val definitions = PerfMetricRegistry.schema()["metrics"] as JsonObject
    private val rates = linkedMapOf(
        "arrivals_per_s" to "requests_arrived", "completions_per_s" to "requests_finished",
        "successful_completions_per_s" to "requests_good", "attempts_per_s" to "attempts_started",
        "input_tokens_per_s" to "input_tokens", "output_tokens_per_s" to "output_tokens",
        "cached_tokens_per_s" to "cached_tokens", "upstream_bytes_per_s" to "upstream_bytes",
        "downstream_bytes_per_s" to "downstream_bytes", "upstream_events_per_s" to "upstream_events",
        "downstream_events_per_s" to "downstream_events",
    )

    fun type(metric: String): String? = (definitions[metric] as? JsonObject)?.let { JsonScalars.str(it, "type") }

    fun summary(query: PerfQuery, data: QueryData): JsonObject {
        val groups = data.points.values.groupBy { it.metric }
        val seconds = (query.untilMs - query.fromMs).toDouble() / MILLIS_PER_SECOND
        return buildJsonObject {
            put(
                "rates",
                buildJsonObject {
                    rates.forEach { (name, metric) ->
                        val values = groups[metric]
                        put(name, numeric(values?.sumOf { it.sum }?.div(seconds)))
                        if (values == null) data.missing.add(metric)
                    }
                    put("denominator", "requested_wall_duration")
                    put("filter_attribution", data.populationSource)
                    put(
                        "coverage",
                        data.populationCoverage[data.populationSource] ?: coverageJson(data.coverage, data.complete),
                    )
                    put(
                        "interpretation",
                        if (data.complete) "complete_population" else "known_observations_with_reported_gaps",
                    )
                },
            )
            put(
                "histograms",
                JsonObject(groups.filterKeys { type(it) == "histogram" }.mapValues { histogram(it.value) }),
            )
            put(
                "counters",
                JsonObject(
                    groups.filterKeys { type(it) == "counter" }.mapValues {
                        counter(it.value, seconds)
                    },
                ),
            )
            put("gauges", JsonObject(groups.filterKeys { type(it) == "gauge" }.mapValues { gauge(it.value) }))
            put(
                "outcomes",
                buildJsonObject {
                    data.points.values.filter { it.metric == "requests_finished" }
                        .groupBy { it.scope.outcome ?: "unknown" }
                        .forEach { (outcome, points) -> put(outcome, points.sumOf { it.sum }) }
                },
            )
            put("goodput", data.goodput)
            put("legacy", data.legacy)
        }
    }

    fun series(data: QueryData): JsonArray = JsonArray(
        data.points.values.groupBy {
            Triple(it.fromMs, it.untilMs, it.scope)
        }.entries.sortedBy { it.key.first }.map { (key, points) ->
            buildJsonObject {
                put("from_ms", key.first)
                put("until_ms", key.second)
                put("scope", scopeJson(key.third))
                put(
                    "metrics",
                    JsonObject(
                        points.groupBy { it.metric }.mapValues { (_, values) ->
                            when (type(values.first().metric)) {
                                "histogram" -> histogram(values)
                                "gauge" -> gauge(values)
                                else -> buildJsonObject {
                                    put(
                                        "sum",
                                        values.sumOf { it.sum },
                                    ); put("count", values.sumOf { it.count })
                                }
                            }
                        },
                    ),
                )
                put("coverage", coverageJson(data.coverage, data.complete))
            }
        },
    )

    private fun histogram(points: List<PerfAggregatePoint>): JsonObject {
        val bounds = PerfMetricRegistry.durationBoundsMs()
        val buckets = List(bounds.size + 1) { index -> points.sumOf { it.buckets.getOrElse(index) { 0L } } }
        val count = points.sumOf { it.count }
        return buildJsonObject {
            put("count", count)
            put("sum", points.sumOf { it.sum })
            put("min", numeric(points.mapNotNull { it.min }.minOrNull()))
            put("max", numeric(points.mapNotNull { it.max }.maxOrNull()))
            put("p50", quantile(buckets, bounds, count, HALF))
            put("p95", quantile(buckets, bounds, count, P95))
            put("p99", quantile(buckets, bounds, count, P99))
            put("bounds_ms", JsonArray(bounds.map(::JsonPrimitive)))
            put("buckets", JsonArray(buckets.map(::JsonPrimitive)))
            put("approximation", "upper_inclusive_bucket_bound; overflow_quantile_unknown")
        }
    }

    private fun quantile(buckets: List<Long>, bounds: List<Double>, count: Long, fraction: Double): JsonElement {
        if (count == 0L) return JsonNull
        val rank = ceil(count * fraction).toLong()
        var seen = 0L
        for ((index, value) in buckets.withIndex()) {
            seen += value
            if (seen >= rank) return numeric(bounds.getOrNull(index))
        }
        return JsonNull
    }

    private fun counter(points: List<PerfAggregatePoint>, seconds: Double): JsonObject = buildJsonObject {
        val sum = points.sumOf { it.sum }
        put("count", points.sumOf { it.count })
        put("sum", sum)
        put("per_s", sum / seconds)
        put("denominator", "requested_wall_duration")
        put("count_unit", "counter_measurements")
        put("aggregation", "sum_event_deltas")
    }

    private fun gauge(points: List<PerfAggregatePoint>): JsonObject = buildJsonObject {
        val covered = points.sumOf { it.coveredMs }
        put("last", numeric(points.maxByOrNull { it.untilMs }?.last))
        put("time_weighted_mean", numeric(if (covered > 0) points.sumOf { it.weightedSum } / covered else null))
        put("covered_ms", covered)
        put("sample_count", points.sumOf { it.count })
        put("scope_count", points.map { it.scope }.distinct().size)
        put("covered_ms_semantics", "sum_over_observed_scopes")
        put("scope_coverage", "observed_scopes_only_missing_expected_scopes_unknown")
        put(
            "aggregation",
            buildJsonObject {
                put("last", "latest_single_scope_reading_not_process_sum")
                put("max", "maximum_observed_scope_sample")
                put("time_weighted_mean", "covered_time_weighted_mean_over_observed_scopes")
            },
        )
        put("per_s", JsonNull)
        put("rate_status", "unsupported_for_sampled_gauge")
        put("min", numeric(points.mapNotNull { it.min }.minOrNull()))
        put("max", numeric(points.mapNotNull { it.max }.maxOrNull()))
    }
}

internal const val MILLIS_PER_SECOND = 1_000.0
private const val HALF = 0.5
private const val P95 = 0.95
private const val P99 = 0.99
