// NEW: v0.4.0 FEATURES.md §6 — the doctor report's log tail (only with --with-logs): the last 500 lines of
// daemon.log from a bounded tail of both generations, each as written and then redacted, as the spec asks (the
// President ruled on Oct 13 that the spec stands over the earlier events-only reduction): a person's or a model's own
// words lose their payload (DoctorLogPayloads), then every credential shape and foreign path (DoctorRedaction.text).
// An unreadable generation is typed metadata beside the lines, never a 501st line. The perf tail lives in
// DoctorReportPerf.kt.
package splice.diagnostics.doctor.report

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import splice.core.config.StatePaths

private const val LOG_TAIL_LINES = 500

/** Enough bytes for well over 500 lines per generation (a line is a few hundred bytes); never a whole file. */
private const val LOG_TAIL_BYTES = 1 shl 20

/** The log tail as the report emits it: `logs`, and `logs_error` when a read failed. */
internal data class LogTail(val lines: JsonArray, val error: String?)

internal class DoctorReportTail(
    private val statePaths: StatePaths,
    private val redaction: DoctorRedaction,
    private val files: DoctorReportFiles,
) {
    private val payloads = DoctorLogPayloads()

    fun logs(): LogTail {
        val read = files.tails(statePaths.logsDir.resolve("daemon.log"), LOG_TAIL_BYTES)
        return LogTail(
            lines = buildJsonArray {
                read.generations.flatMap(payloads::mask).takeLast(LOG_TAIL_LINES)
                    .forEach { add(JsonPrimitive(redaction.text(it))) }
            },
            error = read.error,
        )
    }
}
