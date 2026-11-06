package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.WallClock
import splice.usage.perf.RecordedAccounts
import splice.usage.perf.RequestProjects
import splice.usage.perf.query.PerfQueryJson.envelope
import splice.usage.perf.query.PerfQueryJson.queryError
import splice.usage.perf.query.reduction.PerfEvidenceJson
import splice.usage.perf.query.reduction.PerfEvidenceQueryFold

/** Blocking local query boundary. Hosts must dispatch off inference/Netty threads; work and retained replies are bounded. */
public class PerfQueryService(
    private val sources: PerfQuerySources,
    private val projects: RequestProjects? = null,
    private val accounts: RecordedAccounts? = null,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val work = PerfQueryWork(clock, sources)

    public fun summary(query: PerfQuery): JsonObject = execute(query, "summary") { selected, filter ->
        val data = read(selected, filter)
        envelope(selected, data, PerfQueryMetrics.summary(selected, data))
    }

    public fun series(query: PerfQuery): JsonObject = execute(query, "series") { selected, filter ->
        val data = read(selected, filter)
        envelope(selected, data, buildJsonObject { put("series", PerfQueryMetrics.series(data)) })
    }

    public fun rows(
        query: PerfQuery,
        cursor: String? = null,
        pageSize: Int = PerfQueryParser.DEFAULT_PAGE_SIZE,
    ): JsonObject =
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

    public fun observations(
        query: PerfQuery,
        cursor: String? = null,
        pageSize: Int = PerfQueryParser.DEFAULT_PAGE_SIZE,
    ): JsonObject =
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

    /** Certified collection horizon, not event time. The bounded source read releases its temporary lease. */
    public fun settledUntilMs(head: String? = null): Long? = sources.evidence()?.settledUntilMs(head)

    private fun read(query: PerfQuery, filter: PerfQueryFilter): QueryData {
        val data = QueryData(query)
        data.settledUntilMs = settledUntilMs(query.filters["head"])
        data.collectionMode = sources.collectionMode()?.takeIf { it in setOf("local", "minimal") }
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
        work.execute(query, operation) { resolved -> selected(resolved, read) }

    private fun selected(query: PerfQuery, read: PerfQueryOperation): JsonObject {
        val filter = PerfQueryFilter(query, projects, accounts, sources)
        val unsupported = filter.unsupported()
        return when {
            query.filters["head"]?.let { it !in sources.heads() } == true ->
                queryError("unknown_head", "head_not_available")
            unsupported != null -> queryError("filter_unavailable", unsupported, "unsupported")
            else -> read(query, filter)
        }
    }
}

private val legacyOnlyFilters = setOf("silence_ms", "queued_ms", "resumed", "started_over", "test", "unattributed")

internal fun interface PerfQueryOperation {
    operator fun invoke(query: PerfQuery, filter: PerfQueryFilter): JsonObject
}
