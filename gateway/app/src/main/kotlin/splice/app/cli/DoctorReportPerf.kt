// NEW: v0.4.0 FEATURES.md §6 — the doctor report's perf tail: the last 200 rows per head across
// both file generations, restricted to the 36 numeric perf keys (numbers only), model, outcome
// and account as safe tokens (a model id or label is operator-authored: prose there is omitted)
// and the compact / cache_cold flags as JSON booleans only. Split from
// DoctorReportTail.kt (concentration, 2026-09-13).
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.config.StatePaths
import splice.core.perf.PerfKeys
import splice.core.topology.Topology
import splice.core.util.Cancellables

private const val PERF_TAIL_ROWS = 200

/** Enough bytes for well over 200 rows per generation (a row is under 1 KiB); never a whole file. */
private const val PERF_TAIL_BYTES = 1 shl 20
private val JSON_NUMBER = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?$")
private const val ROWS = "rows"
private const val READ_ERROR = "read_error"
private val IDENTITY_FIELDS: Set<String> = setOf("model", "outcome", "account")

/** A head key the report will read a file for: one path segment, no separators, no escaping. */
private val FILE_KEY = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
private const val UNSAFE_KEY = "head key is not a safe file name; its perf file was not read"
private val FLAG_FIELDS: Set<String> = setOf("compact", "cache_cold")
internal val perfNumericFields: Set<String> = setOf(
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

internal class DoctorReportPerf(
    private val statePaths: StatePaths,
    private val files: DoctorReportFiles,
    private val json: Json,
) {
    /** Per configured head: {rows: the last [PERF_TAIL_ROWS] rows, read_error when a file failed}. */
    fun perf(t: Topology?, names: SafeNames): JsonObject = buildJsonObject {
        t?.heads?.keys?.forEach { key -> put(names.head(key), perfRows(key, names)) }
    }

    /** The reader path is constrained HERE, not by the alias the key prints under: a key that is
     *  not a plain file name never reaches the file system, whatever StatePaths would resolve. */
    private fun perfRows(key: String, names: SafeNames): JsonObject {
        val file = statePaths.perfStatsFile(key).normalize()
        val safe = FILE_KEY.matches(key) && file.startsWith(statePaths.stateDir.normalize())
        val read = if (safe) files.tails(file, PERF_TAIL_BYTES) else FileLines(emptyList(), UNSAFE_KEY)
        return buildJsonObject {
            put(
                ROWS,
                buildJsonArray {
                    read.lines.takeLast(PERF_TAIL_ROWS * 2)
                        .mapNotNull { line -> parsed(line) }
                        .takeLast(PERF_TAIL_ROWS)
                        .forEach { row -> add(allowlisted(row, names)) }
                },
            )
            read.error?.let { put(READ_ERROR, it) }
        }
    }

    private fun parsed(line: String): JsonObject? =
        Cancellables.runCatchingCancellable { json.parseToJsonElement(line).jsonObject }.getOrNull()

    /** Numeric keys keep only JSON numbers; model and outcome keep only safe TOKENS. Anything else a
     *  row carries — another key, a string where a number belongs, an array — is dropped. */
    private fun allowlisted(row: JsonObject, names: SafeNames): JsonObject = buildJsonObject {
        row.forEach { (key, value) ->
            when {
                key in perfNumericFields -> number(value)?.let { put(key, it) }
                key in IDENTITY_FIELDS -> string(value)?.let(names::token)?.let { put(key, it) }
                key in FLAG_FIELDS -> flag(value)?.let { put(key, it) }
            }
        }
    }

    /** A JSON number by the JSON grammar: NaN, Infinity and hex forms that Kotlin's parser tolerates
     *  are not numbers a consumer's parser accepts, so they are dropped, never re-emitted raw. */
    private fun number(value: JsonElement): JsonPrimitive? =
        (value as? JsonPrimitive)?.takeIf { !it.isString && JSON_NUMBER.matches(it.content) }

    private fun string(value: JsonElement): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON boolean only: a string "true" is not a flag. */
    private fun flag(value: JsonElement): Boolean? =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
}
