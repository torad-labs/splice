// NEW: v0.4.0 FEATURES.md §6 — the doctor report's log tail (only with --with-logs): the last 500
// daemon EVENT lines from a bounded tail of both generations, each reduced to its structure by
// DoctorRedaction (non-events in that tail are counted, not shown); an unreadable generation is typed metadata beside
// the lines, never a 501st line. The perf tail lives in DoctorReportPerf.kt.
package splice.app.cli.doctor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import splice.core.config.StatePaths

private const val LOG_TAIL_LINES = 500

/** Enough bytes for well over 500 lines per generation (a line is a few hundred bytes); never a whole file. */
private const val LOG_TAIL_BYTES = 1 shl 20

/** The log tail as the report emits it: `logs`, `logs_dropped_in_tail` (lines of the tail read that
 *  were not daemon events), and `logs_error` when a read failed. */
internal data class LogTail(val lines: JsonArray, val dropped: Int, val error: String?)

internal class DoctorReportTail(
    private val statePaths: StatePaths,
    private val redaction: DoctorRedaction,
    private val files: DoctorReportFiles,
) {
    fun logs(names: SafeNames): LogTail {
        val read = files.tails(statePaths.logsDir.resolve("daemon.log"), LOG_TAIL_BYTES)
        val selection = redaction.logLines(read.lines, names)
        return LogTail(
            lines = buildJsonArray { selection.kept.takeLast(LOG_TAIL_LINES).forEach { add(JsonPrimitive(it)) } },
            dropped = selection.dropped,
            error = read.error,
        )
    }
}
