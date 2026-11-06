package splice.app.sources.observability

import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfHistoryRead
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfSinkStatus
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import java.io.IOException
import java.util.concurrent.Semaphore

private const val HISTORY_READ_RESERVE = 12L * 1_024 * 1_024
private const val HISTORY_READ_INPUT = 1L * 1_024 * 1_024
private const val HISTORY_READ_OUTPUT = 4L * 1_024 * 1_024

internal class PerfHistoryReader(private val files: PerfHistoryFiles, private val costs: PerfSourceCosts) {
    private val queries = Semaphore(1)

    fun read(from: Long, until: Long, now: Long, evidence: List<PerfIssue>, sink: PerfSinkStatus): PerfHistoryRead {
        val resolution = if (from < now - PERF_DAY_MS) PERF_HOUR_MS else PERF_MINUTE_MS
        if (!queries.tryAcquire()) return unavailable(from, until, resolution, "query_busy", sink)
        val lease = PerfWorkBudget.acquire(HISTORY_READ_RESERVE)
        if (lease == null) {
            queries.release()
            return unavailable(from, until, resolution, "memory_busy", sink)
        }
        val started = MonoClock.nowMs()
        costs.begin(true)
        val retainedFrom = (now - HISTORY_RETENTION_MS).coerceAtLeast(0L)
        val scan = HistoryScan(from, until, resolution, evidence.toMutableList(), retainedFrom)
        if (from < retainedFrom) scan.issues += PerfIssue("retention_expired", HISTORY_SINK)
        var retained = false
        try {
            val result = scanned(scan, sink)
            lease.retain(result, result.snapshots.sumOf(PerfHistoryFormat::estimate) + RESULT_OVERHEAD)
            retained = true
            return result
        } finally {
            if (!retained) lease.close()
            costs.end(scan.rows, scan.bytes, (MonoClock.nowMs() - started).coerceAtLeast(0L))
            queries.release()
        }
    }

    private fun scanned(scan: HistoryScan, sink: PerfSinkStatus): PerfHistoryRead {
        try {
            scan.scan(files)
        } catch (_: IOException) {
            scan.issues += PerfIssue("history_read_error", HISTORY_SINK)
        }
        return scan.result(sink)
    }

    private fun unavailable(
        from: Long,
        until: Long,
        resolution: Long,
        code: String,
        sink: PerfSinkStatus,
    ): PerfHistoryRead {
        costs.refused()
        return PerfHistoryRead(
            emptyList(),
            PerfCoverage(from, until, 0L, listOf(PerfIssue(code, HISTORY_SINK)), listOf(sink)),
            resolution,
        )
    }
}

private class HistoryScan(
    private val from: Long,
    private val until: Long,
    private val resolution: Long,
    val issues: MutableList<PerfIssue>,
    private val retainedFrom: Long,
) {
    private val retained = ArrayList<PerfHistoryFile>()
    var bytes = 0L
        private set
    var rows = 0L
        private set

    fun scan(files: PerfHistoryFiles) {
        val inventory = files.inventory()
        val hours = inventory.filter { it.resolutionMs == PERF_HOUR_MS }.map { it.fromMs }.toSet()
        for (entry in inventory.filter { eligible(it, hours) }) {
            if (!read(files, entry)) break
        }
    }

    private fun eligible(entry: PerfHistoryEntry, hours: Set<Long>): Boolean {
        if (entry.fromMs >= until || entry.fromMs + entry.resolutionMs <= maxOf(from, retainedFrom)) return false
        return entry.resolutionMs != PERF_MINUTE_MS || PerfHistoryFormat.hour(entry.fromMs) !in hours
    }

    private fun read(files: PerfHistoryFiles, entry: PerfHistoryEntry): Boolean {
        if (bytes + entry.bytes > HISTORY_READ_INPUT) {
            issues += PerfIssue("query_work_limit", HISTORY_SINK)
            return false
        }
        bytes += entry.bytes
        try {
            val file = files.read(entry)
            val charge = retained.sumOf { item -> item.snapshots.sumOf(PerfHistoryFormat::estimate) }
            if (charge + file.snapshots.sumOf(PerfHistoryFormat::estimate) > HISTORY_READ_OUTPUT) {
                issues += PerfIssue("query_memory_limit", HISTORY_SINK)
                return false
            }
            retained += file
            rows += file.snapshots.sumOf { it.points.size.toLong() }
        } catch (_: IllegalArgumentException) {
            issues += PerfIssue("history_malformed", HISTORY_SINK)
        } catch (_: IOException) {
            issues += PerfIssue("history_read_error", HISTORY_SINK)
        }
        return true
    }

    fun result(sink: PerfSinkStatus): PerfHistoryRead {
        val snapshots = if (resolution == PERF_HOUR_MS) {
            retained.groupBy { PerfHistoryFormat.hour(it.fromMs) }.flatMap { (hour, files) ->
                PerfHistoryFormat.hourly(files, hour).snapshots
            }
        } else {
            retained.flatMap { it.snapshots }
        }
        val observed = coverage(snapshots)
        if (observed < until - from) {
            issues += PerfIssue(
                "coverage_gap",
                HISTORY_SINK,
                detail = "unobserved_ms=${until - from - observed}",
            )
        }
        if (snapshots.any {
                it.fromMs < from || it.untilMs > until
            }
        ) {
            issues += PerfIssue("partial_boundary_bucket", HISTORY_SINK)
        }
        return PerfHistoryRead(
            snapshots,
            PerfCoverage(
                from,
                until,
                observed,
                (
                    issues + snapshots.flatMap {
                        it.coverage.issues
                    }
                    ).distinct().take(MAX_READ_ISSUES),
                listOf(sink),
            ),
            resolution,
        )
    }

    private fun coverage(values: List<PerfAggregateSnapshot>): Long {
        var end = from
        var covered = 0L
        for (snapshot in values.sortedBy { it.fromMs }) {
            val start = maxOf(from, end, snapshot.fromMs)
            val stop = minOf(until, snapshot.untilMs, snapshot.fromMs + snapshot.coverage.observedMs)
            if (stop > start) covered += stop - start
            end = maxOf(end, stop)
        }
        return covered
    }
}

private const val RESULT_OVERHEAD = 16L * 1_024
private const val MAX_READ_ISSUES = 64
private const val HISTORY_RETENTION_MS = 35L * PERF_DAY_MS
