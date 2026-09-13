// NEW (v0.4.0, FEATURES.md §6): the doctor report's tails — the last perf rows per head restricted
// to the named numeric keys plus model and outcome, and (only with --with-logs) the last daemon
// log lines through DoctorRedaction. Split from DoctorReport.kt (concentration, 2026-09-13).
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.app.LogFileSource
import splice.core.config.StatePaths
import splice.core.perf.PerfKeys
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.JsonlSink
import java.nio.file.Path

private const val PERF_TAIL_ROWS = 200
private const val PERF_TAIL_BYTES = 256 * 1024
private const val LOG_TAIL_LINES = 500
private val PERF_FIELDS: Set<String> = setOf(
    "ts",
    "model",
    "outcome",
    "compact",
    PerfKeys.RECV,
    PerfKeys.PARSE,
    PerfKeys.BUILD,
    PerfKeys.GATE,
    PerfKeys.HEADERS,
    PerfKeys.FIRST_BYTE,
    PerfKeys.FIRST_FRAME,
    PerfKeys.FIRST_DELTA,
    PerfKeys.STREAM_END,
    PerfKeys.FINISH,
    PerfKeys.TOTAL,
    PerfKeys.AUTH_MS,
    PerfKeys.BACKOFF_MS,
    PerfKeys.REFRESH_MS,
    PerfKeys.WRITE_MS,
    PerfKeys.USAGE_MS,
    PerfKeys.ATTEMPTS,
    PerfKeys.RETRIES,
    PerfKeys.REFRESHES,
    PerfKeys.REQ_BYTES,
    PerfKeys.UPSTREAM_REQ_BYTES,
    PerfKeys.SSE_BYTES_IN,
    PerfKeys.EVENTS_IN,
    PerfKeys.FRAMES_OUT,
    PerfKeys.CONTENT_FRAMES_OUT,
    PerfKeys.FRAMES_SKIPPED,
    PerfKeys.BYTES_OUT,
    PerfKeys.OUT_TOKENS,
    PerfKeys.IN_TOKENS,
    PerfKeys.CACHED_TOKENS,
    PerfKeys.INFLIGHT,
    PerfKeys.ASYNC_IO_DROPS,
    PerfKeys.TOOLS_EAGER,
    PerfKeys.TOOLS_DEFERRED,
    PerfKeys.SEARCH_ROUNDS,
    PerfKeys.POST_SEND_RETRIES,
)

internal class DoctorReportTail(
    private val statePaths: StatePaths,
    private val redaction: DoctorRedaction,
    private val json: Json,
) {
    /** Last [PERF_TAIL_ROWS] rows per configured head, allowlisted to the perf keys + model/outcome. */
    fun perf(t: Topology?): JsonObject = buildJsonObject {
        t?.heads?.keys?.forEach { key -> put(key, perfRows(statePaths.perfStatsFile(key))) }
    }

    private fun perfRows(file: Path): JsonArray = buildJsonArray {
        Cancellables.runCatchingCancellable { JsonlSink.readTail(file, PERF_TAIL_BYTES) }
            .getOrDefault(emptyList())
            .takeLast(PERF_TAIL_ROWS)
            .mapNotNull { line ->
                Cancellables.runCatchingCancellable { json.parseToJsonElement(line).jsonObject }.getOrNull()
            }
            .forEach { row ->
                add(buildJsonObject { row.filterKeys { it in PERF_FIELDS }.forEach { (k, v) -> put(k, v) } })
            }
    }

    /** The last [LOG_TAIL_LINES] daemon log lines, each through the redaction. */
    fun logs(): JsonArray = buildJsonArray {
        val file = statePaths.logsDir.resolve("daemon.log")
        val tail = Cancellables.runCatchingCancellable { LogFileSource(file).tail(LOG_TAIL_LINES) }.getOrDefault("")
        redaction.logLines(tail.lines()).forEach { add(JsonPrimitive(it)) }
    }
}
