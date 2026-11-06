package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.WallClock
import splice.usage.perf.RecordedAccounts
import splice.usage.perf.RequestProjects
import splice.usage.perf.query.PerfQueryJson.envelope
import splice.usage.perf.query.PerfQueryJson.queryError

/** Blocking local query boundary. Hosts must dispatch off inference/Netty threads; work and retained replies are bounded. */
public class PerfQueryService(
    private val sources: PerfQuerySources,
    private val projects: RequestProjects? = null,
    private val accounts: RecordedAccounts? = null,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val work = PerfQueryWork(clock)

    public fun summary(query: PerfQuery): JsonObject = execute(query, "summary") { selected, filter ->
        val data = read(selected, filter)
        envelope(selected, data, PerfQueryMetrics.summary(selected, data))
    }

    public fun series(query: PerfQuery): JsonObject = execute(query, "series") { selected, filter ->
        val data = read(selected, filter)
        envelope(selected, data, buildJsonObject { put("series", PerfQueryMetrics.series(data)) })
    }

    public fun rows(query: PerfQuery, cursor: String? = null, pageSize: Int = 200): JsonObject =
        execute(query, null) { selected, filter ->
            if (pageSize !in 1..MAX_PAGE_SIZE) {
                queryError("invalid_page_size", "page_size_out_of_bounds")
            } else if (selected.filters.keys.any { it in setOf("transport", "streaming") }) {
                queryError(
                    "legacy_filter_unavailable",
                    "legacy_rows_do_not_record_transport_or_streaming",
                    "unsupported",
                )
            } else {
                PerfQueryRows(sources).read(selected, filter, cursor, pageSize)
            }
        }

    public fun observations(query: PerfQuery, cursor: String? = null, pageSize: Int = 200): JsonObject =
        execute(query, null) { selected, filter ->
            if (pageSize !in 1..MAX_PAGE_SIZE) {
                queryError("invalid_page_size", "page_size_out_of_bounds")
            } else if (selected.filters.keys.any { it in legacyOnlyFilters }) {
                queryError("filter_unavailable", "observation_events_lack_legacy_wait_selectors", "unsupported")
            } else {
                PerfObservationQuery(sources).read(selected, filter, cursor, pageSize)
            }
        }

    public fun diagnose(query: PerfQuery): JsonObject = execute(query, "diagnose") { selected, filter ->
        val data = read(selected, filter)
        envelope(selected, data, PerfDiagnosis.render(selected, data, sources.runtime()))
    }

    public fun schema(): JsonObject = PerfQuerySchema.render()

    private fun read(query: PerfQuery, filter: PerfQueryFilter): QueryData {
        val data = QueryData(query)
        data.goodput = PerfEvidenceJson.initialGoodput(query.objective)
        measured(query, filter, data)
        if (data.counts.isEmpty()) data.counts = PerfPopulationCounts.aggregate(data)
        if (query.source in setOf("auto", "legacy")) PerfLegacyQuery(sources).read(query, filter, data)
        filter.accountUnavailable()?.let { data.issue(it, "accounts") }
        data.configuration = PerfConfigurationQuery.render(sources, query, data)
        return data
    }

    private fun measured(query: PerfQuery, filter: PerfQueryFilter, data: QueryData) {
        if (query.filters.keys.any { it in legacyOnlyFilters }) {
            data.issue("request_filter_requires_legacy_rows", "query")
            return
        }
        if (query.source !in setOf("legacy", "evidence")) PerfAggregateQuery(sources).read(query, filter, data)
        if (query.source in setOf("auto", "evidence")) PerfEvidenceQueryFold(sources).read(query, filter, clock(), data)
    }

    private fun execute(query: PerfQuery, operation: String?, read: PerfQueryOperation): JsonObject =
        work.execute(query, operation) {
            val filter = PerfQueryFilter(query, projects, accounts, sources)
            val unsupported = filter.unsupported()
            val resolved = resolve(query)
            when {
                query.filters["head"]?.let { it !in sources.heads() } == true ->
                    queryError("unknown_head", "head_not_available")
                unsupported != null -> queryError("filter_unavailable", unsupported, "unsupported")
                resolved == null -> queryError("unknown_objective", "objective_unavailable_or_mismatched")
                else -> read(resolved, filter)
            }
        }
    private fun resolve(query: PerfQuery): PerfQuery? {
        val id = query.objectiveId ?: return query
        if (query.objective != null && query.objective.id != id) return null
        val stored = sources.objective(id)
        val objective = stored ?: query.objective
        val mismatch = query.objective?.let { supplied -> stored != null && supplied != stored } == true
        return if (mismatch) null else objective?.let { query.copy(objective = it) }
    }
}

private val legacyOnlyFilters = setOf("silence_ms", "queued_ms", "resumed", "started_over", "test", "unattributed")

internal fun interface PerfQueryOperation {
    operator fun invoke(query: PerfQuery, filter: PerfQueryFilter): JsonObject
}
