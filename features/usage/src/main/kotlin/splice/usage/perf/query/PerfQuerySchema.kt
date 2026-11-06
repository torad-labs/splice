package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.PerfMetricRegistry
import splice.usage.perf.query.PerfQueryJson.strings
import splice.usage.perf.query.reduction.PerfComparisonJson

internal object PerfQuerySchema {
    fun render(): JsonObject = buildJsonObject {
        PerfMetricRegistry.schema().forEach { (key, value) -> put(key, value) }
        put("contract_version", 1)
        put("status", OutcomeTag.OK.wire)
        put("filters", strings(PerfQueryParser.filters.sorted()))
        put("objective_selector", "objective: configured objective ID; unknown IDs are errors")
        put("export_modes", strings(listOf("legacy_rows: legacy_row", "observations: observation")))
        put("partial_bucket_policy", "exclude_intersecting_edges; exact_evidence_used_when_available")
        put("comparison", PerfComparisonJson.semantics())
        put(
            "settled_until_ms",
            "nullable certified collection horizon; caller selects completed bounds explicitly; " +
                "not event-time finality",
        )
    }
}
