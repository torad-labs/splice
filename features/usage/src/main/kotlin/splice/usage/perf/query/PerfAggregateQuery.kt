package splice.usage.perf.query

import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfHistory
import splice.core.util.PerfWorkBudget

internal class PerfAggregateQuery(private val sources: PerfQuerySources) {
    fun read(query: PerfQuery, filter: PerfQueryFilter, data: QueryData) {
        if (query.filters.keys.any { it in rawFilters }) {
            data.issue("aggregate_filter_unsupported", "aggregates")
            return
        }
        val history = if (query.source in setOf("auto", "history")) sources.history() else null
        if (history != null) history(query, filter, data, history)
        val live = if (query.source in setOf("auto", "live")) sources.live() else null
        if (live != null) live(live, query, filter, data, history != null)
        if (history == null && live == null) data.issue("aggregate_source_unavailable", "aggregates")
        data.populationCoverage["aggregate_event_time_scope"] = PerfQueryJson.coverageJson(data.coverage, data.complete)
    }

    private fun live(
        snapshot: PerfAggregateSnapshot,
        query: PerfQuery,
        filter: PerfQueryFilter,
        data: QueryData,
        historyAvailable: Boolean,
    ) {
        if (!historyAvailable) data.coverage = liveCoverage(snapshot, query)
        accept(snapshot, query, filter, data)
        data.complete = data.coverage.observedMs >= query.untilMs - query.fromMs && data.coverage.issues.isEmpty()
    }

    private fun liveCoverage(snapshot: PerfAggregateSnapshot, query: PerfQuery): PerfCoverage {
        val coverage = snapshot.coverage
        val overlap = (
            minOf(query.untilMs, coverage.requestedUntilMs) -
                maxOf(query.fromMs, coverage.requestedFromMs)
            ).coerceAtLeast(0L)
        val outside = (coverage.requestedUntilMs - coverage.requestedFromMs - overlap).coerceAtLeast(0L)
        // Without gap positions, only the observed time that cannot lie outside the query is provable.
        val known = (coverage.observedMs - outside).coerceIn(0L, overlap)
        return coverage.copy(requestedFromMs = query.fromMs, requestedUntilMs = query.untilMs, observedMs = known)
    }

    private fun history(query: PerfQuery, filter: PerfQueryFilter, data: QueryData, history: PerfHistory) {
        val read = history.read(query.fromMs, query.untilMs)
        try {
            data.coverage = read.coverage
            data.resolution = read.resolutionMs
            read.snapshots.forEach { accept(it, query, filter, data) }
            data.complete = read.coverage.observedMs >= query.untilMs - query.fromMs && data.coverage.issues.isEmpty()
        } finally {
            PerfWorkBudget.release(read)
        }
    }

    private fun accept(snapshot: PerfAggregateSnapshot, query: PerfQuery, filter: PerfQueryFilter, data: QueryData) {
        data.identities.add(snapshot.identity)
        snapshot.coverage.issues.forEach { data.issue(it.code, it.source) }
        data.asOf = maxOf(data.asOf, snapshot.untilMs)
        snapshot.points.forEach { point ->
            if (!filter.scope(point.scope)) return@forEach
            if (point.untilMs <= query.fromMs || point.fromMs >= query.untilMs) return@forEach
            if (point.fromMs < query.fromMs || point.untilMs > query.untilMs) {
                data.issue("partial_bucket_edges_excluded", "aggregates")
                data.complete = false
                return@forEach
            }
            if (data.points.size >= MAX_POINTS) {
                data.issue("query_point_limit", "query")
                data.truncated = true
                return@forEach
            }
            // Copies outlive the source lease. Same-minute cumulative replacements overwrite.
            val key = PointKey(snapshot.identity, point.metric, point.scope, point.fromMs)
            data.points[key] = point.copy(buckets = point.buckets.toList())
            data.resolution = maxOf(data.resolution, point.untilMs - point.fromMs)
        }
    }
}

// Stop retained aggregate projection at 4,096 points and expose partial coverage instead of growing without bound.
internal const val MAX_POINTS = 4_096
