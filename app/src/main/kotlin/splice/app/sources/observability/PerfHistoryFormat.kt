package splice.app.sources.observability

import kotlinx.serialization.Serializable
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfIssue

// Minute aggregate filenames and boundaries use sixty thousand milliseconds per complete minute.
internal const val PERF_MINUTE_MS = 60_000L

// Hour rollups cover sixty minute windows, expressed in the source clock's milliseconds.
internal const val PERF_HOUR_MS = 3_600_000L
internal const val PERF_DAY_MS = 24L * PERF_HOUR_MS

// Allocate 176 MiB to aggregate history within the managed instrumentation disk budget.
internal const val PERF_HISTORY_DISK_BYTES = 176L * 1_024 * 1_024

// Cap each aggregate file at 512 KiB so one history decode remains bounded.
internal const val PERF_HISTORY_FILE_BYTES = 512L * 1_024

// Reserve 8 MiB for history maintenance and encoding under the shared work-memory admission.
internal const val PERF_HISTORY_WORK_BYTES = 8L * 1_024 * 1_024
internal const val HISTORY_SINK = "aggregate_history"

@Serializable
internal data class PerfHistoryFile(
    val schemaVersion: Int = 1,
    val resolutionMs: Long,
    val fromMs: Long,
    val snapshots: List<PerfAggregateSnapshot>,
    val revisions: Map<String, Long> = emptyMap(),
)

@Serializable
internal data class PerfHistoryCheckpoint(
    val identity: PerfIdentity,
    val durableUntilMs: Long,
    val clean: Boolean = false,
    val issues: List<PerfIssue> = emptyList(),
)

internal object PerfHistoryFormat {
    fun minute(value: Long): Long = Math.floorDiv(value, PERF_MINUTE_MS) * PERF_MINUTE_MS
    fun hour(value: Long): Long = Math.floorDiv(value, PERF_HOUR_MS) * PERF_HOUR_MS

    fun estimate(snapshot: PerfAggregateSnapshot): Long = PerfHistorySize.snapshot(snapshot)

    fun frozen(snapshot: PerfAggregateSnapshot): PerfAggregateSnapshot = snapshot.copy(
        points = snapshot.points.map { it.copy(buckets = it.buckets.toList()) },
        coverage = snapshot.coverage.copy(
            issues = snapshot.coverage.issues.toList(),
            sinks = snapshot.coverage.sinks.toList(),
        ),
    )

    fun merge(previous: PerfAggregateSnapshot?, incoming: PerfAggregateSnapshot): PerfAggregateSnapshot {
        if (previous == null) return incoming
        if (incoming.untilMs < previous.untilMs) return previous
        val points = previous.points.associateBy { it.metric to it.scope }.toMutableMap()
        incoming.points.forEach { value ->
            val key = value.metric to value.scope
            val prior = points[key]
            if (prior == null || newer(value, prior)) points[key] = value
        }
        return incoming.copy(points = points.values.toList())
    }

    private fun newer(value: PerfAggregatePoint, prior: PerfAggregatePoint): Boolean {
        if (value.count < prior.count) return false
        return value.untilMs >= prior.untilMs && value.coveredMs >= prior.coveredMs
    }

    fun hourly(files: List<PerfHistoryFile>, hour: Long): PerfHistoryFile {
        val snapshots = files.flatMap { it.snapshots }.groupBy { it.identity }.map { (identity, values) ->
            val points = values.flatMap { it.points }.groupBy { it.metric to it.scope }.values.map {
                mergePoints(
                    it,
                    hour,
                )
            }
            PerfAggregateSnapshot(
                identity,
                hour,
                hour + PERF_HOUR_MS,
                points,
                PerfCoverage(
                    hour,
                    hour + PERF_HOUR_MS,
                    values.sumOf { it.coverage.observedMs }.coerceAtMost(PERF_HOUR_MS),
                    values.flatMap { it.coverage.issues }.distinct().take(MAX_MERGED_HISTORY_ISSUES),
                ),
            )
        }
        return PerfHistoryFile(resolutionMs = PERF_HOUR_MS, fromMs = hour, snapshots = snapshots)
    }

    private fun mergePoints(points: List<PerfAggregatePoint>, hour: Long): PerfAggregatePoint {
        val last = points.maxBy { it.untilMs }
        val size = points.maxOf { it.buckets.size }
        return last.copy(
            fromMs = hour,
            untilMs = points.maxOf { it.untilMs },
            count = points.sumOf { it.count },
            sum = points.sumOf { it.sum },
            min = points.mapNotNull { it.min }.minOrNull(),
            max = points.mapNotNull { it.max }.maxOrNull(),
            buckets = List(size) { index -> points.sumOf { it.buckets.getOrElse(index) { 0L } } },
            weightedSum = points.sumOf { it.weightedSum },
            coveredMs = points.sumOf { it.coveredMs },
        )
    }

    // Retain sixty-four distinct source issues when reducing minute snapshots into an hourly record.
    private const val MAX_MERGED_HISTORY_ISSUES = 64
}
