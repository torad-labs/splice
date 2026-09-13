import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.control.PerfRow
import splice.control.api.PerfSummary
import splice.control.api.PerfWindow

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
                row(40_000, "error:upstream-failed", "total" to 300L, "async_io_drops" to 1L),
                row(41_000, "error:upstream-failed", "total" to 300L),
                row(42_000, "error:upstream-failed", "total" to 300L),
                row(43_000, "error:upstream-failed", "total" to 300L, "async_io_drops" to 3L),
                row(50_000, "client_abort", "first_byte" to 90L, "total" to 200L),
            )

    private fun n(o: JsonObject, key: String) = o.getValue(key).jsonPrimitive.content

    @Test
    fun `slow, retried and failed rows produce distinguishable numbers`() {
        val s = PerfSummary { now }.json(controlled(), PerfWindow.H1, now)
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
        assertEquals(0.25, n(s, "failure_share").toDouble(), 1e-9)
        assertEquals("6", n(s, "retries"))
        assertEquals("3", n(s, "refreshes"))
        assertEquals(0.5, n(s, "cache_hit_ratio").toDouble(), 1e-9)
        assertEquals("7", n(s, "peak_inflight"))
        assertEquals("2", n(s, "telemetry_dropped"))
    }

    @Test
    fun `empty data is reported as empty, never as zero latency`() {
        val s = PerfSummary { now }.json(emptyList(), PerfWindow.H24, now)
        assertEquals("0", n(s, "count"))
        assertEquals("true", n(s, "empty"))
        assertNull(s["time_before_first_byte_ms"])
        assertNull(s["total_ms"])
        assertNull(s["failure_share"])
        assertEquals("true", n(s, "clamped"))
        assertEquals("0", n(s, "covers_ms"))
    }

    @Test
    fun `the window cuts rows by timestamp and says when the files cannot fill it`() {
        val rows = listOf(fast(10 * hour), fast(30 * hour), fast(2 * hour))
        val day = PerfSummary { now }.json(rows, PerfWindow.H24, now)
        assertEquals("2", n(day, "count"))
        assertEquals("false", n(day, "clamped"))
        assertEquals((24 * hour).toString(), n(day, "covers_ms"))
        val week = PerfSummary { now }.json(rows, PerfWindow.D7, now)
        assertEquals("3", n(week, "count"))
        assertEquals("true", n(week, "clamped"))
        assertEquals((30 * hour).toString(), n(week, "covers_ms"))
        assertTrue(n(week, "note").contains("30h"), n(week, "note"))
        assertEquals(PerfWindow.H1, PerfSummary().window("1h"))
        assertNull(PerfSummary().window("2h"))
    }
}
