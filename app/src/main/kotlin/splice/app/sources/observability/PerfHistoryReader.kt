package splice.app.sources.observability

import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfHistoryRead
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfSinkStatus
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import java.io.IOException
import java.util.concurrent.Semaphore

private const val HISTORY_READ_RESERVE = 12L * 1_024 * 1_024

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
        val scan = PerfHistoryScan(from, until, resolution, evidence, retainedFrom)
        if (from < retainedFrom) scan.error("retention_expired")
        var retained = false
        try {
            val result = scanned(scan, sink)
            lease.retain(
                result,
                result.snapshots.sumOf(
                    PerfHistoryFormat::estimate,
                ) + PerfHistorySize.coverage(result.coverage) + RESULT_OVERHEAD,
            )
            retained = true
            return result
        } finally {
            if (!retained) lease.close()
            costs.end(scan.rows, scan.bytes, (MonoClock.nowMs() - started).coerceAtLeast(0L))
            queries.release()
        }
    }

    private fun scanned(scan: PerfHistoryScan, sink: PerfSinkStatus): PerfHistoryRead {
        try {
            scan.scan(files)
        } catch (_: IOException) {
            scan.error("history_read_error")
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

private const val RESULT_OVERHEAD = 4_096L
private const val HISTORY_RETENTION_MS = 35L * PERF_DAY_MS
