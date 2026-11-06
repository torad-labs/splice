package splice.app.sources.observability

import splice.core.perf.observation.PerfIssue
import java.io.IOException

internal class PerfHistoryRetention(private val files: PerfHistoryFiles) {
    fun maintain(now: Long): List<PerfIssue> {
        val issues = ArrayList<PerfIssue>()
        prune(now, issues)
        val entries = files.inventory()
        val cutoff = PerfHistoryFormat.hour(now - PERF_DAY_MS)
        val old = entries.filter { it.resolutionMs == PERF_MINUTE_MS && it.fromMs < cutoff }
        // One bounded hour per checkpoint; reads also roll older minutes up without mutating files.
        old.groupBy { PerfHistoryFormat.hour(it.fromMs) }.entries.firstOrNull()?.let { (hour, minutes) ->
            compact(hour, minutes, issues)
        }
        prune(now, issues)
        return issues.groupBy { it.code }.map { (code, values) -> PerfIssue(code, HISTORY_SINK, values.size.toLong()) }
    }

    private fun compact(hour: Long, minutes: List<PerfHistoryEntry>, issues: MutableList<PerfIssue>) {
        try {
            if (files.existing(hour, PERF_HOUR_MS) == null) {
                val fold = PerfHourFold(hour)
                minutes.forEach { fold.add(files.read(it)) }
                files.write(fold.result())
            }
            minutes.forEach(files::remove)
        } catch (_: IOException) {
            issues += PerfIssue("hour_compaction_deferred", HISTORY_SINK)
        } catch (_: IllegalArgumentException) {
            issues += PerfIssue("hour_compaction_malformed", HISTORY_SINK)
        }
    }

    fun reserve(now: Long): List<PerfIssue> = ArrayList<PerfIssue>().also { prune(now, it) }

    private fun prune(now: Long, issues: MutableList<PerfIssue>) {
        var held = files.inventory().sumOf { it.bytes }
        for (entry in files.inventory()) {
            val expired = entry.fromMs + entry.resolutionMs <= now - RETENTION_MS
            if (expired || held + PERF_HISTORY_FILE_BYTES + HISTORY_METADATA_RESERVE > PERF_HISTORY_DISK_BYTES) {
                files.remove(entry)
                held -= entry.bytes
                issues += PerfIssue(if (expired) "retention_expired" else "disk_evicted", HISTORY_SINK)
            }
        }
    }
}

private const val RETENTION_MS = 35L * PERF_DAY_MS
private const val HISTORY_METADATA_RESERVE = 128L * 1_024
