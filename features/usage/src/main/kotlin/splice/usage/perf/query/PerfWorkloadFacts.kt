package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.UsageMeasurementStamp
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfScope

internal data class MeasuredWorkload(val partition: JsonObject?, val reasons: Set<String>)

/** Each tuple is one canonical client request bill. No attempt sizes can be recovered from absorbed totals. */
internal object PerfWorkloadFacts {
    private val fields = linkedMapOf(
        "input_tokens" to "input_tokens",
        "output_tokens" to "output_tokens",
        "cached_tokens" to "cached_tokens",
        "cache_write_tokens" to "usage_cache_write_tokens",
        "cache_write_1h_tokens" to "usage_cache_write_1h_tokens",
        "absorbed_rounds" to "usage_absorbed_rounds",
        "cut_rounds" to "usage_cut_rounds",
        "no_request" to "usage_no_request",
    )

    fun measure(events: List<PerfObservation>): MeasuredWorkload {
        val reasons = linkedSetOf<String>()
        val terminal = events.firstOrNull { it.kind == PerfEventKind.REQUEST_FINISHED }
        if (terminal == null) reasons.add("request_terminal_missing")
        val usage = events.filter { it.kind == PerfEventKind.USAGE_RECORDED }.singleOrNull()
        if (usage == null) reasons.add("canonical_usage_missing_or_ambiguous")
        if (usage?.values?.get("missing_usage") != 0.0) reasons.add("canonical_usage_incomplete")
        if (!observed(usage)) reasons.add("usage_measurement_presence_unknown")
        val values = fields.mapValues { integer(usage?.values?.get(it.value)) }
        reasons.addAll(valueIssues(values))
        val representative = representative(events)
        val scope = representative.scope.copy(outcome = representative.outcome ?: representative.scope.outcome)
        if (!resolved(scope)) reasons.add("request_scope_unresolved")
        if (scope.outcome in setOf(null, "unknown", "?")) reasons.add("terminal_outcome_unknown")
        val partition = if (values.values.any { it == null }) null else partition(scope, values)
        return MeasuredWorkload(partition, reasons)
    }

    fun representative(events: List<PerfObservation>): PerfObservation =
        events.firstOrNull { it.kind == PerfEventKind.REQUEST_FINISHED }
            ?: events.lastOrNull { it.kind == PerfEventKind.CHECKPOINT } ?: events.last()

    fun resolved(scope: PerfScope): Boolean = listOf(
        listOf(scope.head, scope.model, scope.transport, scope.work).all { it != null && it != "unknown" },
        scope.local != null,
        scope.compact != null,
        scope.streaming != null,
    ).all { it }

    private fun observed(usage: PerfObservation?): Boolean =
        UsageMeasurementStamp.fields.keys.all { usage?.values?.get(it) == 1.0 }

    private fun valueIssues(values: Map<String, Long?>): Set<String> = buildSet {
        if (values.values.any { it == null }) add("canonical_usage_fields_missing")
        if ((values["absorbed_rounds"] ?: 0) > 0) add("absorbed_round_sizes_unknown")
        if ((values["cut_rounds"] ?: 0) > 0) add("cut_round_usage_missing")
        if (inconsistent(values)) add("canonical_usage_invalid")
    }

    private fun inconsistent(values: Map<String, Long?>): Boolean {
        val cache = (values["cached_tokens"] ?: 0) + (values["cache_write_tokens"] ?: 0)
        return listOf(
            values["no_request"]?.let { it !in 0L..1L } == true,
            cache > (values["input_tokens"] ?: Long.MAX_VALUE),
            (values["cache_write_1h_tokens"] ?: 0) > (values["cache_write_tokens"] ?: Long.MAX_VALUE),
        ).any { it }
    }

    private fun partition(scope: PerfScope, values: Map<String, Long?>): JsonObject = buildJsonObject {
        put("scope", PerfQueryJson.scopeJson(scope))
        values.forEach { (key, value) -> put(key, PerfQueryJson.numeric(value)) }
    }

    private fun integer(value: Double?): Long? = value?.takeIf {
        it.isFinite() && it >= 0 && it <= MAX_EXACT_INTEGER
    }?.takeIf { it == it.toLong().toDouble() }?.toLong()
}

private const val MAX_EXACT_INTEGER = 9_007_199_254_740_991.0
