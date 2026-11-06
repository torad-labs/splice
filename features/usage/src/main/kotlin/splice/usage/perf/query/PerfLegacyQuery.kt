package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.PerfKeys
import splice.core.perf.observation.PerfMetricRegistry
import splice.core.perf.observation.PerfProjection
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsWindow
import splice.usage.perf.PerfSummary
import splice.usage.perf.ProjectedPerfRowsSource
import splice.usage.perf.query.PerfQueryJson.numeric
import splice.usage.perf.query.PerfQueryJson.textValue

internal class PerfLegacyQuery(private val sources: PerfQuerySources) {
    fun read(query: PerfQuery, filter: PerfQueryFilter, data: QueryData) {
        val heads = sources.heads().filter { query.filters["head"]?.let { asked -> it == asked } != false }
        data.legacy = JsonObject(
            heads.associateWith { head ->
                val source = sources.rows(head)
                if (source == null) {
                    data.issue("legacy_source_unavailable", "legacy")
                    JsonObject(emptyMap())
                } else if (source is ProjectedPerfRowsSource) {
                    source.projected(query.fromMs) { fold(it.window, head, filter, data) }
                } else {
                    fold(source.window(query.fromMs), head, filter, data)
                }
            },
        )
    }

    private fun fold(read: PerfRowsWindow, head: String, filter: PerfQueryFilter, data: QueryData): JsonObject {
        if (read.readError != null) data.issue("legacy_read_error", "legacy")
        if (read.skipped > 0) data.issue("legacy_malformed_rows", "legacy")
        val population = select(read, head, filter)
        val rows = population.rows
        val matching = population.matching
        val local = population.local
        val bounded = matching <= MAX_LEGACY_ROWS
        if (!bounded) data.issue("legacy_metric_work_limit", "query")
        return buildJsonObject {
            put("count", matching - local)
            put("local_steps", local)
            put("row_count", matching)
            put("empty", matching == 0L)
            put("read_status", if (read.readError == null) "readable" else "unreadable")
            put("metrics_complete", bounded)
            put("record_unit", "legacy_row")
            put("oldest_held_ms", numeric(read.oldestHeldTs))
            if (bounded) {
                PerfSummary().metrics(rows.filter { it.fields[PerfKeys.LOCAL_STEP] != 1L })
                    .forEach { (key, value) -> put(key, value) }
            }
        }
    }
    private fun select(read: PerfRowsWindow, head: String, filter: PerfQueryFilter): LegacyPopulation {
        val rows = ArrayList<PerfRow>()
        var matching = 0L
        var local = 0L
        read.rows.forEach { row ->
            if (filter.row(head, row)) {
                matching++
                if (row.fields[PerfKeys.LOCAL_STEP] == 1L) local++
                if (rows.size < MAX_LEGACY_ROWS) rows.add(row)
            }
        }
        return LegacyPopulation(rows, matching, local)
    }
}

private const val MAX_LEGACY_ROWS = 4_096

internal object PerfLegacyRows {
    fun render(head: String, row: PerfRow): JsonObject = buildJsonObject {
        put("head", head)
        put("ts", row.ts)
        put("outcome", row.outcome)
        put("model", textValue(row.facts.model))
        put("session_id", textValue(row.transcript.sessionId))
        put("turn_id", textValue(row.turnId))
        put("compact", row.facts.compact?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put(
            "fields",
            JsonObject(
                row.fields.filterKeys { PerfMetricRegistry.permits(it, PerfProjection.LOCAL) }
                    .mapValues { JsonPrimitive(it.value) },
            ),
        )
    }
}

private data class LegacyPopulation(val rows: List<PerfRow>, val matching: Long, val local: Long)
