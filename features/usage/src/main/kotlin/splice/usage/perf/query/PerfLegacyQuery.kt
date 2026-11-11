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
    /** A period that begins beyond [LEGACY_REACH_MS] is not read at all: no command is listed and the reply says why,
     *  so the measurements around it still answer instead of the whole read running into its deadline. */
    fun read(query: PerfQuery, filter: PerfQueryFilter, nowMs: Long, data: QueryData) {
        if (nowMs - query.fromMs > LEGACY_REACH_MS) {
            data.issue("legacy_window_beyond_kept_rows", LEGACY_SOURCE)
            return
        }
        val heads = sources.heads().filter { query.filters["head"]?.let { asked -> it == asked } != false }
        data.legacy = JsonObject(
            heads.associateWith { head ->
                val source = sources.rows(head)
                if (source == null) {
                    data.issue("legacy_source_unavailable", LEGACY_SOURCE)
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
        if (read.readError != null) data.issue("legacy_read_error", LEGACY_SOURCE)
        if (read.skipped > 0) data.issue("legacy_malformed_rows", LEGACY_SOURCE)
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
            // The same fold the Usage page's table reads, over the same turns, so the two agree to the last digit.
            if (bounded) PerfSummary().metrics(rows).forEach { (key, value) -> put(key, value) }
        }
    }

    /** The turns of one head that match, held by reference so a busy command's week costs eight bytes a row; the local
     *  steps are counted apart and never enter the figures. Past [MAX_LEGACY_ROWS] it only counts. */
    private fun select(read: PerfRowsWindow, head: String, filter: PerfQueryFilter): LegacyPopulation {
        val rows = ArrayList<PerfRow>()
        var matching = 0L
        var local = 0L
        read.rows.forEach { row ->
            if (filter.row(head, row)) {
                matching++
                when {
                    row.fields[PerfKeys.LOCAL_STEP] == 1L -> local++
                    rows.size < MAX_LEGACY_ROWS -> rows.add(row)
                }
            }
        }
        return LegacyPopulation(rows, matching, local)
    }
}

// why: the rows a head keeps decoded hold a week of its busiest command (about 240,000 requests, PerfRowsCache), and this
// machine's claude-splice was 121,185 over 7 days. Four times that is a command 4x busier than the busiest on record,
// and what it holds is a row list plus four primitive sample arrays (about 50 MB at the ceiling). The old 4,096 stopped
// every command of a day's traffic at its count alone, while the Usage page folded the same rows without a limit.
private const val MAX_LEGACY_ROWS = 1_048_576

// The source every issue of this read names, as the console and the schema know it.
private const val LEGACY_SOURCE = "legacy"

// why: a period that begins further back than a week reaches into the archives behind the rows each head keeps decoded:
// this machine's claude-splice holds about 1.5 GB of them for 30 days, which takes longer than the 15 s the control
// deadline gives a read (measured live, Nov 10, 15.2 s and 15.8 s, then a 503 that held both perf slots). The two extra
// hours cover a fall-back day and the clock between parsing a period and reading it, as UsageReadWarmup's week does.
private const val LEGACY_REACH_MS = (7L * 24 + 2) * 3_600_000L

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
