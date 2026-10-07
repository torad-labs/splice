package splice.usage.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.perf.PerfKeys
import splice.usage.UsageHead
import splice.usage.UsageHeads
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

class PerfSummaryTest {

    private val now = 1_789_312_411_660L
    private val hour = 3_600_000L

    private fun row(ageMs: Long, outcome: String = "ok", vararg fields: Pair<String, Long>) =
        PerfRow(now - ageMs, outcome, mapOf("in_tokens" to 1000L, "cached_tokens" to 500L, *fields))

    private fun fast(age: Long) =
        row(age, "ok", "first_byte" to 100L, "stream_end" to 400L, "total" to 500L, "inflight" to 2L)

    private fun controlled(): List<PerfRow> =
        (1..10).map { fast(it * 1000L) } +
            listOf(
                row(20_000, "ok", "first_byte" to 5000L, "stream_end" to 9000L, "total" to 9500L, "inflight" to 7L),
                row(21_000, "ok", "first_byte" to 6000L, "stream_end" to 9500L, "total" to 9900L),
                row(30_000, "ok", "first_byte" to 120L, "stream_end" to 420L, "total" to 520L, "retries" to 2L, "refreshes" to 1L),
                row(31_000, "ok", "first_byte" to 130L, "stream_end" to 430L, "total" to 530L, "retries" to 2L, "refreshes" to 1L),
                row(32_000, "ok", "first_byte" to 140L, "stream_end" to 440L, "total" to 540L, "retries" to 2L, "refreshes" to 1L),
                // async_io_drops is the daemon's CUMULATIVE counter: oldest first these read 0, 1, 1, 3 —
                // two drops happened inside the window, whatever every later row keeps carrying.
                row(40_000, "error:upstream-failed", "total" to 300L, "async_io_drops" to 3L),
                row(41_000, "error:upstream-failed", "total" to 300L, "async_io_drops" to 1L),
                row(42_000, "error:upstream-failed", "total" to 300L, "async_io_drops" to 1L),
                row(43_000, "error:upstream-failed", "total" to 300L, "async_io_drops" to 0L),
                row(50_000, "client_abort", "first_byte" to 90L, "total" to 200L),
            ).sortedBy { it.ts }

    private fun n(o: JsonObject, key: String) = o.getValue(key).jsonPrimitive.content

    @Test
    fun `fleet timing pools actual requests in the same read and clock as command summaries`() {
        val reads = mutableListOf<Pair<String, Long>>()
        val busy = (1..100).map { row(it.toLong(), "ok", PerfKeys.FIRST_BYTE to 100L) }
        val local = (1..200).map { row(it.toLong(), "ok", PerfKeys.LOCAL_STEP to 1L, PerfKeys.FIRST_BYTE to 0L) }
        val older = (1..200).map { row(hour + it, "ok", PerfKeys.FIRST_BYTE to 0L) }
        val histories = mapOf(
            "busy" to busy + local + older + row(500),
            "sparse" to listOf(row(1000, "ok", PerfKeys.FIRST_BYTE to 10_000L)),
            "slower" to listOf(row(2000, "ok", PerfKeys.FIRST_BYTE to 20_000L)),
        )
        val heads = histories.map { (key, rows) ->
            UsageHead(
                key = key,
                label = key,
                usage = HeadUsageSource { UsageView(0, 0, null) },
                warnPct = 90,
                warnTokens5h = 0,
                perfRows = PerfRowsSource { since ->
                    reads += key to since
                    PerfRowsWindow(rows)
                },
            )
        }
        var clockReads = 0
        val payloads = PerfPayloads(UsageHeads { heads }) {
            clockReads++
            now
        }
        val result = Json.parseToJsonElement(payloads.summaryJson(PerfWindow.H1)).jsonObject
        val pooled = result.getValue("time_before_first_byte_ms").jsonObject
        assertEquals("102", n(pooled, "count"), "local steps, old rows and absent marks never become timings")
        assertEquals("100", n(pooled, "p50"), "one busy command must outweigh sparse command medians")
        assertEquals("100", n(pooled, "p95"))
        assertEquals("20000", n(pooled, "max"))
        val commands = result.getValue("heads").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("100", "10000", "20000"),
            commands.map { n(it.getValue("time_before_first_byte_ms").jsonObject, "p50") },
        )
        assertEquals("101", n(commands.first(), "count"))
        assertEquals("200", n(commands.first(), "local_steps"))
        assertEquals(histories.keys.map { it to now - hour }, reads, "every source is read once at one cutoff")
        assertEquals(1, clockReads)
    }

    @Test
    fun `fleet timing is absent when no request carries a mark`() {
        val payloads = PerfPayloads(UsageHeads { emptyList() }) { now }
        val result = Json.parseToJsonElement(payloads.summaryJson(PerfWindow.H1)).jsonObject
        assertNull(result["time_before_first_byte_ms"], "an absent distribution is never a zero-latency claim")
    }

    @Test
    fun `cache ratio uses paired observations and never invents a miss for an input-only failure`() {
        val measured = row(1000)
        val partial = PerfRow(now - 2000, "error:upstream-failed", mapOf(PerfKeys.IN_TOKENS to 1000L))
        val unknown = PerfRow(now - 3000, "error:upstream-failed", emptyMap())
        val orphanCache = PerfRow(now - 4000, "error:upstream-failed", mapOf(PerfKeys.CACHED_TOKENS to 9999L))
        val s = PerfSummary { now }.json(
            PerfRowsWindow(listOf(measured, partial, unknown, orphanCache)),
            PerfWindow.H1,
            now,
        )
        assertEquals(0.5, n(s, "cache_hit_ratio").toDouble(), 1e-9)
        val absent = PerfSummary { now }.json(PerfRowsWindow(listOf(partial, unknown)), PerfWindow.H1, now)
        assertEquals(JsonNull, absent["cache_hit_ratio"])
    }

    @Test
    fun `slow, retried and failed rows produce distinguishable numbers`() {
        val s = PerfSummary { now }.json(PerfRowsWindow(controlled()), PerfWindow.H1, now)
        assertEquals("20", n(s, "count"))
        assertEquals("false", n(s, "empty"))
        val firstByte = s.getValue("time_before_first_byte_ms").jsonObject
        assertEquals("100", n(firstByte, "p50"))
        assertEquals("6000", n(firstByte, "p95"))
        val streaming = s.getValue("time_streaming_ms").jsonObject
        assertEquals("300", n(streaming, "p50"))
        assertEquals("4000", n(streaming, "p95"))
        val outcomes = s.getValue("outcomes").jsonObject
        assertEquals("4", n(outcomes, "error:upstream-failed"))
        assertEquals("1", n(outcomes, "client_abort"))
        assertEquals("15", n(outcomes, "ok"))
        assertEquals(0.20, n(s, "failure_share").toDouble(), 1e-9)
        val shares = s.getValue("failure_shares").jsonObject
        assertEquals(0.20, n(shares, "error:upstream-failed").toDouble(), 1e-9)
        assertNull(shares["client_abort"], "a stopped request is not a failure")
        assertNull(shares["ok"])
        val total = s.getValue("total_ms").jsonObject
        assertEquals("500", n(total, "p50"))
        assertEquals("9500", n(total, "p95"))
        assertEquals("9900", n(total, "max"))
        assertEquals("6", n(s, "retries"))
        assertEquals("3", n(s, "refreshes"))
        assertEquals(0.5, n(s, "cache_hit_ratio").toDouble(), 1e-9)
        assertEquals("7", n(s, "peak_inflight"))
        assertEquals("3", n(s, "io_drops_in_window"), "the counter rose 0 -> 1 -> 3 inside the window")
        assertEquals("0", n(s, "unattributed"))
    }

    @Test
    fun `local code steps are reported apart from turns and do not dilute turn latency or failure shares`() {
        val rows = listOf(
            row(3000, "ok", "attempts" to 1L, "total" to 100L),
            row(2000, "error:upstream-failed", "attempts" to 1L, "total" to 200L),
            row(1500, "error:rate-limited", "attempts" to 0L, "total" to 50L),
            row(1000, "ok", "attempts" to 0L, "local_step" to 1L, "total" to 10_000L),
            row(500, "ok", "total" to 300L), // legacy rows without markers are turns
        )
        val s = PerfSummary { now }.json(PerfRowsWindow(rows), PerfWindow.H1, now)
        assertEquals("4", n(s, "count"))
        assertEquals("1", n(s, "local_steps"))
        assertEquals("4", n(s.getValue("total_ms").jsonObject, "count"))
        assertEquals("300", n(s.getValue("total_ms").jsonObject, "max"))
        assertEquals(0.5, n(s, "failure_share").toDouble(), 1e-9)
    }

    @Test
    fun `io drops are the counter's increases, a restart is taken whole, and a row that never grew is zero`() {
        val steady = (1..5).map { row(it * 1000L, "ok", "async_io_drops" to 7L) }
        assertEquals("0", n(PerfSummary { now }.json(PerfRowsWindow(steady), PerfWindow.H1, now), "io_drops_in_window"))
        val restarted = listOf(
            row(5000, "ok", "async_io_drops" to 7L),
            row(4000, "ok", "async_io_drops" to 9L),
            row(3000, "ok", "async_io_drops" to 2L),
            row(2000, "ok", "async_io_drops" to 2L),
            row(1000, "ok", "async_io_drops" to 5L),
        )
        val summary = PerfSummary { now }.json(PerfRowsWindow(restarted), PerfWindow.H1, now)
        assertEquals("7", n(summary, "io_drops_in_window"), "2 + 2 + 3")
    }

    @Test
    fun `an unparseable outcome is unattributed, shown but never a failure`() {
        val rows = listOf(fast(1000), row(2000, "?", "total" to 5L), row(3000, "client_abort", "total" to 5L))
        val s = PerfSummary { now }.json(PerfRowsWindow(rows), PerfWindow.H1, now)
        assertEquals("1", n(s, "unattributed"))
        assertEquals(0.0, n(s, "failure_share").toDouble(), 1e-9, "a stop and an unknown outcome are not failures")
        assertNull(s.getValue("failure_shares").jsonObject["?"])
        assertEquals("1", n(s.getValue("outcomes").jsonObject, "?"))
    }

    @Test
    fun `stops remain counted without failures while watchdog cancellation stays a failure`() {
        val tags = listOf(
            OutcomeTag.OK.wire,
            "?",
            OutcomeTag.CLIENT_ABORT.wire,
            OutcomeTag.CANCELLED.wire,
            OutcomeTags.error("stopped"),
            "error:new-ending",
        )
        val rows = tags.mapIndexed { index, tag -> row((index + 1) * 1000L, tag) }
        val s = PerfSummary { now }.json(PerfRowsWindow(rows), PerfWindow.H1, now)
        assertEquals("6", n(s, "count"))
        assertEquals(tags.toSet(), s.getValue("outcomes").jsonObject.keys)
        assertEquals(2.0 / 6, n(s, "failure_share").toDouble(), 1e-9)
        val failures = s.getValue("failure_shares").jsonObject
        assertEquals(setOf(OutcomeTag.CANCELLED.wire, "error:new-ending"), failures.keys)
        assertEquals(1.0 / 6, n(failures, OutcomeTag.CANCELLED.wire).toDouble(), 1e-9)
    }

    @Test
    fun `no rows at all is said as such, a future row covers nothing, and a read error rides through`() {
        val none = PerfSummary { now }.json(PerfRowsWindow(emptyList()), PerfWindow.H24, now)
        assertEquals("false", n(none, "clamped"), "nothing recorded is not a truncated window")
        assertEquals("no perf rows recorded yet", n(none, "note"))
        val future = PerfSummary { now }.json(PerfRowsWindow(listOf(fast(-2 * hour))), PerfWindow.H24, now)
        assertEquals("0", n(future, "covers_ms"), "a clock step never reads as negative coverage")
        assertEquals("true", n(future, "clamped"))
        assertTrue(n(future, "note").contains("0m of rows"), n(future, "note"))
        val short = PerfSummary { now }.json(PerfRowsWindow(listOf(fast(45 * 60_000L))), PerfWindow.H1, now)
        assertTrue(n(short, "note").contains("45m of rows"), "minutes below an hour: " + n(short, "note"))
        val corrupt = PerfSummary { now }.json(PerfRowsWindow(emptyList(), skipped = 3), PerfWindow.H24, now)
        assertEquals("no valid perf rows read; 3 unparseable lines skipped", n(corrupt, "note"))
        assertEquals("3", n(corrupt, "skipped_lines"))
        assertNull(none["skipped_lines"], "nothing skipped, nothing said")
        val unread = PerfRowsWindow(emptyList(), readError = "codex-perf.jsonl.1: denied")
        val broken = PerfSummary { now }.json(unread, PerfWindow.H1, now)
        assertEquals("codex-perf.jsonl.1: denied", n(broken, "read_error"))
        assertEquals("false", n(broken, "coverage_known"))
        assertTrue(n(broken, "note").startsWith("no perf rows read; a generation could not be read"), n(broken, "note"))
        val fiveMinutes = 5 * 60_000L
        val partial = PerfRowsWindow(
            listOf(fast(fiveMinutes)),
            oldestHeldTs = now - fiveMinutes,
            readError = "x.1: denied",
        )
        val unknown = PerfSummary { now }.json(partial, PerfWindow.H24, now)
        assertEquals("false", n(unknown, "clamped"), "an unread generation may hold the rest: never a clamp")
        assertEquals("false", n(unknown, "coverage_known"))
        assertTrue(n(unknown, "note").contains("5m of rows read, coverage unknown"), n(unknown, "note"))
        assertEquals("1", n(unknown, "count"), "what was read still counts")
    }

    @Test
    fun `io drops follow file order, not the wall clock`() {
        val stepped = listOf(row(1000, "ok", "async_io_drops" to 1L), row(2000, "ok", "async_io_drops" to 3L))
        val s = PerfSummary { now }.json(PerfRowsWindow(stepped), PerfWindow.H1, now)
        assertEquals("2", n(s, "io_drops_in_window"), "appended 1 then 3 although the clock stepped back")
    }

    @Test
    fun `the first in-window counter is measured against the last row before the cutoff`() {
        val rows = listOf(row(2000, "ok", "async_io_drops" to 2L), row(1000, "ok", "async_io_drops" to 2L))
        val blind = PerfSummary { now }.json(PerfRowsWindow(rows), PerfWindow.H1, now)
        assertEquals("0", n(blind, "io_drops_in_window"), "no baseline: the first sample is the baseline")
        val restarted = PerfSummary { now }.json(PerfRowsWindow(rows, dropsBefore = 8L), PerfWindow.H1, now)
        assertEquals("2", n(restarted, "io_drops_in_window"), "8 before the cutoff, a restart, then 2: two drops")
        val grew = PerfSummary { now }.json(PerfRowsWindow(rows, dropsBefore = 1L), PerfWindow.H1, now)
        assertEquals("1", n(grew, "io_drops_in_window"), "1 before the cutoff -> 2 -> 2")
    }

    @Test
    fun `empty data is reported as empty, never as zero latency`() {
        val s = PerfSummary { now }.json(PerfRowsWindow(emptyList()), PerfWindow.H24, now)
        assertEquals("0", n(s, "count"))
        assertEquals("true", n(s, "empty"))
        assertNull(s["time_before_first_byte_ms"])
        assertNull(s["total_ms"])
        assertNull(s["failure_share"])
        assertEquals("false", n(s, "clamped"), "no row anywhere: nothing to clamp")
        assertEquals("0", n(s, "covers_ms"))
    }

    @Test
    fun `the window cuts rows by timestamp and says when the files cannot fill it`() {
        val rows = listOf(fast(10 * hour), fast(30 * hour), fast(2 * hour))
        val day = PerfSummary { now }.json(PerfRowsWindow(rows), PerfWindow.H24, now)
        assertEquals("2", n(day, "count"))
        assertEquals("false", n(day, "clamped"))
        assertEquals((24 * hour).toString(), n(day, "covers_ms"))
        val week = PerfSummary { now }.json(PerfRowsWindow(rows), PerfWindow.D7, now)
        assertEquals("3", n(week, "count"))
        assertEquals("true", n(week, "clamped"))
        assertEquals((30 * hour).toString(), n(week, "covers_ms"))
        assertTrue(n(week, "note").contains("30h"), n(week, "note"))
        assertEquals(PerfWindow.H1, PerfSummary().window("1h"))
        assertNull(PerfSummary().window("2h"))
    }

    /** A source with the file cutoff (rows before since are never returned) plus retention evidence. */
    private fun source(all: List<PerfRow>) = PerfRowsSource { since ->
        PerfRowsWindow(all.filter { it.ts >= since }, oldestHeldTs = all.minOfOrNull { it.ts })
    }

    @Test
    fun `a quiet week over files that reach past the window is sparse traffic, not a clamped window`() {
        val day = 24 * hour
        val reaching = source(listOf(fast(8 * day), fast(6 * day)))
        val week = PerfSummary { now }.summarize(reaching, PerfWindow.D7)
        assertEquals("1", n(week, "count"), "the 8d row is outside the window")
        assertEquals("false", n(week, "clamped"), "the files hold an 8d row: retention covers the window")
        assertEquals((7 * day).toString(), n(week, "covers_ms"))
        assertNull(week["note"])

        val truncated = source(listOf(fast(6 * day)))
        val cut = PerfSummary { now }.summarize(truncated, PerfWindow.D7)
        assertEquals("1", n(cut, "count"))
        assertEquals("true", n(cut, "clamped"), "nothing older than 6d exists: the files cannot fill 7d")
        assertEquals((6 * day).toString(), n(cut, "covers_ms"))

        val blindRows = listOf(fast(8 * day), fast(6 * day))
        val blind = PerfRowsSource { since -> PerfRowsWindow(blindRows.filter { it.ts >= since }) }
        assertEquals("true", n(PerfSummary { now }.summarize(blind, PerfWindow.D7), "clamped"), "no evidence")
    }

    @Test
    fun `last_ts names the head's newest row even when the window excludes it`() {
        val day = 24 * hour
        val idle = listOf(fast(3 * day), fast(2 * day))
        val held = PerfRowsSource { since ->
            PerfRowsWindow(idle.filter { it.ts >= since }, newestHeldTs = idle.maxOf { it.ts })
        }
        val hourly = PerfSummary { now }.summarize(held, PerfWindow.H1)
        assertEquals("0", n(hourly, "count"), "nothing inside the hour")
        assertEquals((now - 2 * day).toString(), n(hourly, "last_ts"), "the store's newest row, not the window's")

        val stepped = listOf(fast(10 * 60_000L), fast(20 * 60_000L), fast(5 * 60_000L))
        val inWindow = PerfSummary { now }.json(PerfRowsWindow(stepped), PerfWindow.H1, now)
        assertEquals(
            (now - 5 * 60_000L).toString(),
            n(inWindow, "last_ts"),
            "a source that cannot say: the newest RETURNED row by ts, not the last appended",
        )

        val none = PerfSummary { now }.json(PerfRowsWindow(emptyList()), PerfWindow.H24, now)
        assertTrue(none.containsKey("last_ts"), "always present, so the console never reads undefined")
        assertEquals(JsonNull, none["last_ts"], "no row anywhere is null, never 0 (which reads as 1970)")
        val blind = PerfRowsSource { since -> PerfRowsWindow(idle.filter { it.ts >= since }) }
        val unsaid = PerfSummary { now }.summarize(blind, PerfWindow.H1)
        assertEquals(JsonNull, unsaid["last_ts"], "the documented fallback: the window's rows, and it has none")
    }
}
