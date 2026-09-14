// NEW: v0.4.0 FEATURES.md §3 — the windowed performance summary — one object per head per
// window (1h / 24h / 7d) computed from the perf JSONL rows. Labels name WHAT was measured, never
// a cause: "time before first byte" is the wait until the upstream's first byte, "time streaming"
// is first byte to stream end. Empty data is reported as empty (count 0, no percentiles), never
// as zero-latency traffic; a window the files cannot fill says so (clamped + covers_ms), no rows
// at all says THAT, and a generation that could not be read is carried as read_error, never as
// short retention, and while a generation is unread the coverage is UNKNOWN, never a clamp.
// io_drops_in_window is a LOWER BOUND on the async file-io writes the daemon dropped during the
// window: the per-row counter is cumulative per process and the rows carry no process identity,
// so a restart is only visible as a decrease, and a new process whose count catches the old one
// up hides its drops. A dropped perf row is absent from the file, so this is the evidence that
// something is missing, not a count of missing rows.
package splice.control.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.control.PerfRow
import splice.control.PerfRowsSource
import splice.control.PerfRowsWindow
import splice.core.perf.PerfKeys
import splice.core.util.WallClock
import kotlin.math.ceil

private const val P50 = 0.50
private const val P95 = 0.95
private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_HOUR = 3_600_000L
private const val HOURS_PER_DAY = 24L
private const val DAYS_PER_WEEK = 7L
private const val OK = "ok"

/** A row whose outcome could not be parsed: shown under this tag, never counted as a failure. */
public const val UNATTRIBUTED_OUTCOME: String = "?"

public enum class PerfWindow(public val label: String, public val ms: Long) {
    H1("1h", MS_PER_HOUR),
    H24("24h", HOURS_PER_DAY * MS_PER_HOUR),
    D7("7d", DAYS_PER_WEEK * HOURS_PER_DAY * MS_PER_HOUR),
}

public class PerfSummary(private val clock: WallClock = WallClock { System.currentTimeMillis() }) {

    /** The window named by [label], or null when it is not one of 1h / 24h / 7d. */
    public fun window(label: String?): PerfWindow? = PerfWindow.entries.firstOrNull { it.label == label }

    public fun summarize(source: PerfRowsSource?, window: PerfWindow): JsonObject {
        val now = clock()
        return json(source?.window(now - window.ms) ?: PerfRowsWindow(emptyList()), window, now)
    }

    /** [read].rows = the window's rows (older ones are ignored). [PerfRowsWindow.oldestHeldTs] = the
     *  oldest row the files hold at all: coverage is measured from it, so a quiet week over files that
     *  reach past the window is NOT reported clamped; without it the oldest returned row is the only
     *  evidence. No row anywhere is "no perf rows recorded yet", not a clamp. A read error rides
     *  through as is and makes the coverage UNKNOWN (coverage_known false, never clamped): the unread
     *  generation may hold the rest of the window. */
    public fun json(read: PerfRowsWindow, window: PerfWindow, now: Long): JsonObject {
        val inWindow = read.rows.filter { it.ts >= now - window.ms }
        val coverage = coverage(read, window, now)
        return buildJsonObject {
            put("window", window.label)
            put("count", inWindow.size)
            put("empty", inWindow.isEmpty())
            put("coverage_known", coverage.known)
            put("clamped", coverage.clamped)
            put("covers_ms", minOf(coverage.coveredMs, window.ms))
            coverage.note()?.let { put("note", it) }
            read.readError?.let { put("read_error", it) }
            if (read.skipped > 0) put("skipped_lines", read.skipped)
            if (inWindow.isNotEmpty()) metrics(inWindow, read.dropsBefore).forEach { (k, v) -> put(k, v) }
        }
    }

    private fun coverage(read: PerfRowsWindow, window: PerfWindow, now: Long): Coverage {
        val oldest = read.oldestHeldTs ?: read.rows.minOfOrNull { it.ts }
        // A row stamped in the future (a clock step) covers nothing; coverage never reads negative.
        val covered = oldest?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        val known = read.readError == null
        val clamped = known && oldest != null && covered < window.ms
        return Coverage(oldest != null, covered, known, clamped, read.skipped)
    }

    private inner class Coverage(
        val held: Boolean,
        val coveredMs: Long,
        val known: Boolean,
        val clamped: Boolean,
        val skipped: Int,
    ) {
        fun note(): String? = when {
            !known && !held -> "no perf rows read; a generation could not be read, coverage unknown"
            !known -> "a generation could not be read: ${span(coveredMs)} of rows read, coverage unknown"
            // Every line was rejected: the file is broken (or written by something else), not idle.
            !held && skipped > 0 -> "no valid perf rows read; $skipped unparseable lines skipped"
            !held -> "no perf rows recorded yet"
            clamped -> "the perf files hold ${span(coveredMs)} of rows, less than the window"
            else -> null
        }
    }

    /** Whole hours once there are any, minutes below that: a 45-minute reach is "45m", never "0h". */
    private fun span(ms: Long): String =
        if (ms >= MS_PER_HOUR) "${ms / MS_PER_HOUR}h" else "${ms / MS_PER_MINUTE}m"

    private fun metrics(rows: List<PerfRow>, dropsBefore: Long?): JsonObject = buildJsonObject {
        latencies(rows).forEach { (k, v) -> put(k, v) }
        val byOutcome = rows.groupingBy { it.outcome }.eachCount().toSortedMap()
        putJsonObject("outcomes") { byOutcome.forEach { (tag, n) -> put(tag, n) } }
        // A row without a parseable outcome is unattributed: shown, never counted as a failure.
        val failures = byOutcome.filterKeys { it != OK && it != UNATTRIBUTED_OUTCOME }
        put("failure_share", failures.values.sum().toDouble() / rows.size)
        // Per tag, so four upstream failures and one client abort read 0.20 and 0.05, not one 0.25.
        putJsonObject("failure_shares") { failures.forEach { (tag, n) -> put(tag, n.toDouble() / rows.size) } }
        put("unattributed", byOutcome[UNATTRIBUTED_OUTCOME] ?: 0)
        counters(rows, dropsBefore).forEach { (k, v) -> put(k, v) }
    }

    private fun latencies(rows: List<PerfRow>): JsonObject = buildJsonObject {
        stats(rows.mapNotNull { it.fields[PerfKeys.FIRST_BYTE] })?.let { put("time_before_first_byte_ms", it) }
        stats(rows.mapNotNull(::streaming))?.let { put("time_streaming_ms", it) }
        stats(rows.mapNotNull { it.fields[PerfKeys.TOTAL] })?.let { put("total_ms", it) }
    }

    private fun counters(rows: List<PerfRow>, dropsBefore: Long?): JsonObject = buildJsonObject {
        put("retries", rows.sumOf { it.fields[PerfKeys.RETRIES] ?: 0L })
        put("refreshes", rows.sumOf { it.fields[PerfKeys.REFRESHES] ?: 0L })
        val inTokens = rows.sumOf { it.fields[PerfKeys.IN_TOKENS] ?: 0L }
        val cached = rows.sumOf { it.fields[PerfKeys.CACHED_TOKENS] ?: 0L }
        put("cache_hit_ratio", if (inTokens > 0) cached.toDouble() / inTokens else null)
        put("peak_inflight", rows.maxOfOrNull { it.fields[PerfKeys.INFLIGHT] ?: 0L })
        put("io_drops_in_window", ioDrops(rows, dropsBefore))
    }

    /** The per-row counter is cumulative for the daemon process: the drops that happened inside the
     *  window are the increases between consecutive rows in FILE order (the sampling order; the
     *  wall clock may step), the first one measured against the last row before the cutoff when the
     *  source kept it. A decrease is a restart: the new process's count is taken whole. A restart
     *  whose new count catches the old one up is invisible, so the total is a lower bound. */
    private fun ioDrops(rows: List<PerfRow>, dropsBefore: Long?): Long {
        var previous: Long? = dropsBefore
        var drops = 0L
        rows.forEach { row ->
            val current = row.fields[PerfKeys.ASYNC_IO_DROPS] ?: return@forEach
            val before = previous
            drops += when {
                before == null -> 0L
                current < before -> current
                else -> current - before
            }
            previous = current
        }
        return drops
    }

    /** First byte to stream end; absent when either mark is missing (a failed turn has no stream). */
    private fun streaming(row: PerfRow): Long? {
        val end = row.fields[PerfKeys.STREAM_END] ?: return null
        return row.fields[PerfKeys.FIRST_BYTE]?.let { end - it }
    }

    /** {count, p50, p95, max} of [values], or null when there are none: the one implementation
     *  both /api/perf and /api/perf/summary print. */
    internal fun stats(values: List<Long>): JsonObject? {
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
