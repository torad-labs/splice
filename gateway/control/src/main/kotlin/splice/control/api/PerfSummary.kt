// NEW (v0.4.0, FEATURES.md §3): the windowed performance summary — one object per head per
// window (1h / 24h / 7d) computed from the perf JSONL rows. Labels name WHAT was measured, never
// a cause: "time before first byte" is the wait until the upstream's first byte, "time streaming"
// is first byte to stream end. Empty data is reported as empty (count 0, no percentiles), never
// as zero-latency traffic, and a window the files cannot fill says so (clamped + covers_ms).
package splice.control.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.control.PerfRow
import splice.control.PerfRowsSource
import splice.core.perf.PerfKeys
import kotlin.math.ceil

private const val P50 = 0.50
private const val P95 = 0.95
private const val MS_PER_HOUR = 3_600_000L
private const val HOURS_PER_DAY = 24L
private const val DAYS_PER_WEEK = 7L
private const val OK = "ok"

public enum class PerfWindow(public val label: String, public val ms: Long) {
    H1("1h", MS_PER_HOUR),
    H24("24h", HOURS_PER_DAY * MS_PER_HOUR),
    D7("7d", DAYS_PER_WEEK * HOURS_PER_DAY * MS_PER_HOUR),
}

public class PerfSummary(private val clock: () -> Long = System::currentTimeMillis) {

    /** The window named by [label], or null when it is not one of 1h / 24h / 7d. */
    public fun window(label: String?): PerfWindow? = PerfWindow.entries.firstOrNull { it.label == label }

    public fun summarize(source: PerfRowsSource?, window: PerfWindow): JsonObject {
        val now = clock()
        val rows = source?.rowsSince(now - PerfWindow.D7.ms).orEmpty()
        return json(rows, window, now)
    }

    /** [rows] = everything the files hold that is at most 7d old; the window is cut from it here. */
    public fun json(rows: List<PerfRow>, window: PerfWindow, now: Long): JsonObject {
        val since = now - window.ms
        val inWindow = rows.filter { it.ts >= since }
        val oldest = rows.minOfOrNull { it.ts }
        val covered = oldest?.let { now - it } ?: 0L
        val clamped = covered < window.ms
        return buildJsonObject {
            put("window", window.label)
            put("count", inWindow.size)
            put("empty", inWindow.isEmpty())
            put("clamped", clamped)
            put("covers_ms", minOf(covered, window.ms))
            if (clamped) put("note", "the perf files hold ${covered / MS_PER_HOUR}h of rows, less than the window")
            if (inWindow.isNotEmpty()) metrics(inWindow).forEach { (k, v) -> put(k, v) }
        }
    }

    private fun metrics(rows: List<PerfRow>): JsonObject = buildJsonObject {
        latencies(rows).forEach { (k, v) -> put(k, v) }
        putJsonObject("outcomes") {
            rows.groupingBy { it.outcome }.eachCount().toSortedMap().forEach { (tag, n) -> put(tag, n) }
        }
        put("failure_share", 1.0 - rows.count { it.outcome == OK }.toDouble() / rows.size)
        counters(rows).forEach { (k, v) -> put(k, v) }
    }

    private fun latencies(rows: List<PerfRow>): JsonObject = buildJsonObject {
        stats(rows.mapNotNull { it.fields[PerfKeys.FIRST_BYTE] })?.let { put("time_before_first_byte_ms", it) }
        stats(rows.mapNotNull(::streaming))?.let { put("time_streaming_ms", it) }
        stats(rows.mapNotNull { it.fields[PerfKeys.TOTAL] })?.let { put("total_ms", it) }
    }

    private fun counters(rows: List<PerfRow>): JsonObject = buildJsonObject {
        put("retries", rows.sumOf { it.fields[PerfKeys.RETRIES] ?: 0L })
        put("refreshes", rows.sumOf { it.fields[PerfKeys.REFRESHES] ?: 0L })
        val inTokens = rows.sumOf { it.fields[PerfKeys.IN_TOKENS] ?: 0L }
        val cached = rows.sumOf { it.fields[PerfKeys.CACHED_TOKENS] ?: 0L }
        put("cache_hit_ratio", if (inTokens > 0) cached.toDouble() / inTokens else null)
        put("peak_inflight", rows.maxOfOrNull { it.fields[PerfKeys.INFLIGHT] ?: 0L })
        put("telemetry_dropped", rows.count { (it.fields[PerfKeys.ASYNC_IO_DROPS] ?: 0L) > 0L })
    }

    /** First byte to stream end; absent when either mark is missing (a failed turn has no stream). */
    private fun streaming(row: PerfRow): Long? {
        val end = row.fields[PerfKeys.STREAM_END] ?: return null
        return row.fields[PerfKeys.FIRST_BYTE]?.let { end - it }
    }

    private fun stats(values: List<Long>): JsonObject? {
        val sorted = values.sorted().takeIf { it.isNotEmpty() } ?: return null
        return buildJsonObject {
            put("count", sorted.size)
            put("p50", percentile(sorted, P50))
            put("p95", percentile(sorted, P95))
            put("max", sorted.last())
        }
    }

    /** Nearest-rank percentile on a pre-sorted list. */
    private fun percentile(sorted: List<Long>, q: Double): Long =
        sorted[ceil(q * sorted.size).toInt().coerceIn(1, sorted.size) - 1]
}
