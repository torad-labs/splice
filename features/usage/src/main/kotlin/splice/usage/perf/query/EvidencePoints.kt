package splice.usage.perf.query

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.OutcomeTags
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfMetricRegistry
import splice.core.perf.observation.PerfObjectiveSet
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfProjection
import splice.usage.perf.query.PerfEvidenceWindow.inside
import splice.usage.perf.query.PerfQueryJson.textValue

internal class EvidencePoints(private val query: PerfQuery) {
    val supported = setOf(
        "requests_arrived", "requests_finished", "requests_good", "requests_bad", "requests_unknown",
        "attempts_started", "attempts_finished", "input_tokens", "output_tokens", "cached_tokens", "missing_usage",
        "arrival_to_terminal_ms", "arrival_to_first_meaningful_content_ms", "arrival_to_first_reasoning_ms",
        "arrival_to_first_text_ms", "arrival_to_first_tool_ms", "arrival_to_first_client_byte_ms",
        "queue_wait_ms", "upstream_gap_max_ms", "downstream_gap_max_ms", "attempt_duration_ms",
    )
    private val result = LinkedHashMap<PointKey, PerfAggregatePoint>()

    fun points(): Map<PointKey, PerfAggregatePoint> = result

    fun add(events: List<PerfObservation>) {
        events.firstOrNull { it.kind == PerfEventKind.REQUEST_ARRIVED }?.let { count(it, "requests_arrived") }
        val terminal = events.firstOrNull { it.kind == PerfEventKind.REQUEST_FINISHED }
        if (terminal != null) terminal(terminal)
        val usage = events.firstOrNull { it.kind == PerfEventKind.USAGE_RECORDED } ?: terminal
        usage?.let { measurements(it, it.values.filterKeys { key -> key in USAGE_METRICS }) }
        attempts(events)
    }

    private fun terminal(event: PerfObservation) {
        count(event, "requests_finished")
        val outcome = event.outcome ?: "?"
        val metric = when {
            OutcomeTags.isClean(outcome) -> "requests_good"
            outcome == "?" -> "requests_unknown"
            else -> "requests_bad"
        }
        count(event, metric)
        measurements(event, event.values.filterKeys { it !in USAGE_METRICS })
    }

    private fun attempts(events: List<PerfObservation>) {
        events.filter { it.kind == PerfEventKind.ATTEMPT_STARTED }.distinctBy { it.links.attemptId }
            .forEach { count(it, "attempts_started") }
        events.filter { it.kind == PerfEventKind.ATTEMPT_FINISHED }.distinctBy { it.links.attemptId }.forEach {
            count(it, "attempts_finished")
            measurements(it, it.values)
        }
    }

    private fun count(event: PerfObservation, metric: String) = measurements(event, mapOf(metric to 1.0))

    private fun measurements(event: PerfObservation, values: Map<String, Double>) {
        if (!inside(event, query)) return
        values.filter { (key, value) -> accepted(key, value) }.forEach { (metric, value) -> add(event, metric, value) }
    }

    private fun accepted(key: String, value: Double): Boolean = key in supported && value.isFinite() && value >= 0

    private fun add(event: PerfObservation, metric: String, value: Double) {
        val start = maxOf(query.fromMs, event.stamp.wallMs / MINUTE_MS * MINUTE_MS)
        val end = minOf(query.untilMs, event.stamp.wallMs / MINUTE_MS * MINUTE_MS + MINUTE_MS)
        val scope = event.scope.copy(outcome = event.outcome ?: event.scope.outcome)
        val key = PointKey(event.stamp.identity, metric, scope, start)
        val previous = result[key]
        result[key] = PerfAggregatePoint(
            metric, scope, start, end, (previous?.count ?: 0) + 1,
            (previous?.sum ?: 0.0) + value, minOf(previous?.min ?: value, value),
            maxOf(previous?.max ?: value, value), buckets(metric, value, previous),
        )
    }

    private fun buckets(metric: String, value: Double, previous: PerfAggregatePoint?): List<Long> {
        if (PerfQueryMetrics.type(metric) != "histogram") return emptyList()
        val bounds = PerfMetricRegistry.durationBoundsMs()
        val bucket = bounds.indexOfFirst { value <= it }.takeIf { it >= 0 } ?: bounds.size
        return List(bounds.size + 1) {
            (previous?.buckets?.getOrNull(it) ?: 0L) + if (it == bucket) 1L else 0L
        }
    }
}

internal object PerfEvidenceJson {
    fun initialGoodput(objective: PerfObjectiveSet?): JsonObject {
        if (objective == null) return unconfiguredGoodput()
        return JsonObject(
            unconfiguredGoodput() + buildJsonObject {
                put("status", "unavailable")
                put("objective_id", objective.id)
                put("objective_revision", objective.revision)
                put("reason", "complete_request_evidence_required")
            },
        )
    }
    fun unconfiguredGoodput(): JsonObject = buildJsonObject {
        put("status", "unconfigured")
        put("objective_id", JsonNull)
        put("objective_revision", JsonNull)
        put("attribution", "arrival_window")
        listOf("eligible", "good", "bad", "pending", "unknown", "per_s", "good_fraction").forEach { put(it, JsonNull) }
    }

    fun timelineEvent(event: PerfObservation): JsonObject = buildJsonObject {
        put("kind", event.kind.name)
        put("wall_ms", event.stamp.wallMs)
        put("monotonic_ms", event.stamp.monotonicMs)
        put("attempt_id", textValue(event.links.attemptId))
        put("tool_id", textValue(event.links.toolId))
        put("trace_id", textValue(event.links.traceId))
        put("outcome", textValue(event.outcome))
        put(
            "measurements",
            JsonObject(
                event.values.filter { (metric, value) ->
                    value.isFinite() && PerfMetricRegistry.permits(metric, PerfProjection.LOCAL)
                }.mapValues { JsonPrimitive(it.value) },
            ),
        )
    }
}

private val USAGE_METRICS = setOf("input_tokens", "output_tokens", "cached_tokens", "missing_usage")
private const val MINUTE_MS = 60_000L
