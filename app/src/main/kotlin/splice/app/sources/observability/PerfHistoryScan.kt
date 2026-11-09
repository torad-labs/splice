package splice.app.sources.observability

import splice.core.perf.observation.PerfHistoryRead
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfSinkStatus
import splice.core.util.MonoClock
import java.io.IOException

/** Each source file is decoded once, then released after its counts enter the bounded fold. */
internal class PerfHistoryScan(
    private val from: Long,
    private val until: Long,
    resolution: Long,
    evidence: List<PerfIssue>,
    private val retainedFrom: Long,
) {
    private val fold = PerfReadFold(resolution, until - from)
    private val coverage = PerfReadCoverage(from, until, evidence)
    private val started = MonoClock.nowMs()
    var bytes = 0L
        private set
    var rows = 0L
        private set

    fun error(code: String) { coverage.error(code) }

    fun scan(files: PerfHistoryFiles) {
        val inventory = files.inventory(maxOf(from, retainedFrom), until, INVENTORY_BYTES)
        val hours = inventory.filter { it.resolutionMs == PERF_HOUR_MS }.map { it.fromMs }.toSet()
        for (entry in inventory.filter { eligible(it, hours) }) {
            if (!read(entry)) break
        }
    }

    fun result(sink: PerfSinkStatus): PerfHistoryRead =
        PerfHistoryRead(fold.snapshots(), coverage.result(sink), fold.resolution)

    private fun eligible(entry: PerfHistoryEntry, hours: Set<Long>): Boolean {
        if (entry.fromMs >= until || entry.fromMs + entry.resolutionMs <= maxOf(from, retainedFrom)) return false
        return entry.resolutionMs != PERF_MINUTE_MS || PerfHistoryFormat.hour(entry.fromMs) !in hours
    }

    private fun read(entry: PerfHistoryEntry): Boolean {
        val limited = bytes + entry.bytes > PERF_HISTORY_DISK_BYTES || MonoClock.nowMs() - started > QUERY_TIME_MS
        if (limited) {
            error("query_work_limit")
            return false
        }
        bytes += entry.bytes
        return try {
            PerfHistoryStream.read(entry) { snapshot ->
                fold.add(snapshot)
                coverage.add(snapshot)
                rows += snapshot.points.size
            }
            true
        } catch (_: IllegalArgumentException) {
            error("history_malformed")
            true
        } catch (failure: IOException) {
            val limit = failure.message == "query_memory_limit"
            error(if (limit) "query_memory_limit" else "history_read_error")
            !limit
        }
    }
}

// Stop history scanning after thirty seconds so a large retained window cannot monopolize query work.
private const val QUERY_TIME_MS = 30_000L

// Limit the path inventory to a 1 MiB charge before decoding aggregate files.
private const val INVENTORY_BYTES = 1L * 1_024 * 1_024
