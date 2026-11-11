package splice.usage.perf.query

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfHistory
import splice.core.util.PerfWorkBudget

/**
 * The recorder's aggregates for a query: its files for the minutes it has written and let go of, and its live
 * minutes for the rest.
 *
 * The live recorder's copy of a minute is the newer one, so the files answer only for the minutes before the ones it
 * still holds. Until Nov 11, 2026 both were read for the whole period and a live minute replaced its file by its
 * start time. A read that coarsens the files (any longer than a few minutes) no longer has that start time, so both
 * copies counted: for 6:42 to 6:52 AM CT that day the files held 92 completions, the live recorder 31 of the same,
 * and the summary answered 123.
 */
internal class PerfAggregateQuery(private val sources: PerfQuerySources) {
    fun read(query: PerfQuery, filter: PerfQueryFilter, data: QueryData) {
        if (query.filters.keys.any { it in rawFilters }) {
            data.issue("aggregate_filter_unsupported", "aggregates")
            return
        }
        val history = if (query.source in setOf("auto", "history")) sources.history() else null
        val live = if (query.source in setOf("auto", "live")) sources.live() else null
        val share = Share(query, history, live)
        share.files?.let { history(share, it, filter, data) }
        live?.let { live(it, share, filter, data) }
        if (history == null && live == null) data.issue("aggregate_source_unavailable", "aggregates")
        data.populationCoverage["aggregate_event_time_scope"] = PerfQueryJson.coverageJson(data.coverage, data.complete)
    }

    private fun live(snapshot: PerfAggregateSnapshot, share: Share, filter: PerfQueryFilter, data: QueryData) {
        val query = share.query
        if (share.files == null) {
            data.coverage = liveCoverage(snapshot, query.fromMs, query.untilMs)
        } else if (share.liveTail) {
            // The files were read up to the live minutes, which observed the rest of the period.
            val tail = liveCoverage(snapshot, share.liveFloor, query.untilMs)
            data.coverage = data.coverage.copy(
                requestedUntilMs = query.untilMs,
                observedMs = data.coverage.observedMs + tail.observedMs,
            )
        }
        accept(snapshot, share.liveFloor, share.query, filter, data)
        data.complete = data.coverage.observedMs >= query.untilMs - query.fromMs && data.coverage.issues.isEmpty()
    }

    private fun liveCoverage(snapshot: PerfAggregateSnapshot, from: Long, until: Long): PerfCoverage {
        val coverage = snapshot.coverage
        val overlap = (
            minOf(until, coverage.requestedUntilMs) -
                maxOf(from, coverage.requestedFromMs)
            ).coerceAtLeast(0L)
        val outside = (coverage.requestedUntilMs - coverage.requestedFromMs - overlap).coerceAtLeast(0L)
        // Without gap positions, only the observed time that cannot lie outside the query is provable.
        val known = (coverage.observedMs - outside).coerceIn(0L, overlap)
        return coverage.copy(
            requestedFromMs = from,
            requestedUntilMs = until,
            observedMs = known,
            issues = coverage.issues.filter { it.overlaps(from, until) },
        )
    }

    private fun history(share: Share, history: PerfHistory, filter: PerfQueryFilter, data: QueryData) {
        val query = share.query
        val read = history.read(query.fromMs, share.filesUntil)
        try {
            data.coverage = read.coverage.copy(
                issues = read.coverage.issues.filter { it.overlaps(query.fromMs, query.untilMs) },
            )
            data.resolution = read.resolutionMs
            read.snapshots.forEach { accept(it, query.fromMs, query, filter, data) }
            data.complete = read.coverage.observedMs >= query.untilMs - query.fromMs && data.coverage.issues.isEmpty()
        } finally {
            PerfWorkBudget.release(read)
        }
    }

    /** Takes the points of [snapshot] that start at [floor] or later and lie inside [query]. */
    private fun accept(
        snapshot: PerfAggregateSnapshot,
        floor: Long,
        query: PerfQuery,
        filter: PerfQueryFilter,
        data: QueryData,
    ) {
        data.identities.add(snapshot.identity)
        snapshot.coverage.issues.filter { it.overlaps(query.fromMs, query.untilMs) }
            .forEach { data.issue(it.code, it.source) }
        data.asOf = maxOf(data.asOf, snapshot.untilMs)
        snapshot.points.forEach { point ->
            if (!filter.scope(point.scope) || elsewhere(point, floor, query)) return@forEach
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

    /** Not this source's to answer: outside the period, or a live minute from before [floor], which the files hold. */
    private fun elsewhere(point: PerfAggregatePoint, floor: Long, query: PerfQuery): Boolean {
        val outside = point.untilMs <= query.fromMs || point.fromMs >= query.untilMs
        return outside || point.fromMs in query.fromMs until floor
    }

    /** How the files and the live recorder share a period: the files answer up to [filesUntil], the live minutes
     *  from [liveFloor]. */
    private class Share(val query: PerfQuery, history: PerfHistory?, live: PerfAggregateSnapshot?) {
        private val liveFrom: Long? = live?.takeIf { history != null }?.let(::firstMinute)

        /** Where the files stop answering: the first live minute, or the period's end when none is held. */
        val filesUntil: Long = minOf(query.untilMs, liveFrom ?: query.untilMs)

        /** The files, when some of the period is theirs to answer. */
        val files: PerfHistory? = history?.takeIf { filesUntil > query.fromMs }

        /** The earliest start of a live point that counts. */
        val liveFloor: Long = liveFrom ?: query.fromMs

        /** Whether the live minutes observed the end of a period whose start the files answered. */
        val liveTail: Boolean = files != null && liveFrom != null && liveFrom < query.untilMs

        /** The start of the unbroken run of minutes [live] holds up to its newest, or null when it holds none. An
         *  older minute it holds apart from that run waits for a write its file already has the last copy of. */
        private fun firstMinute(live: PerfAggregateSnapshot): Long? {
            val minutes = live.points.mapTo(HashSet()) { it.fromMs }
            var first = minutes.maxOrNull() ?: return null
            while (first - LIVE_MINUTE_MS in minutes) first -= LIVE_MINUTE_MS
            return first
        }
    }
}

// Stop retained aggregate projection at 4,096 points and expose partial coverage instead of growing without bound.
internal const val MAX_POINTS = 4_096

// The live recorder keeps its aggregates in one-minute cells, each point starting on a minute.
private const val LIVE_MINUTE_MS = 60_000L
