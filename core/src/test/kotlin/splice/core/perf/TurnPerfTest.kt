// NEW: TurnPerf contract — marks are elapsed-at-completion (fake clock), markOnce keeps the
// first value, counters sum, timed() attributes block duration, and perfLine renders marks in
// pipeline order with counters after the bar.
package splice.core.perf

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TurnPerfTest {

    private class FakeClock(var now: Long = 1_000L) {
        fun tick(ms: Long) {
            now += ms
        }
    }

    @Test
    fun `marks record elapsed at completion and re-mark overwrites`() {
        val clock = FakeClock()
        val perf = TurnPerf { clock.now }
        clock.tick(5)
        perf.mark(PerfKeys.RECV)
        clock.tick(10)
        perf.mark(PerfKeys.HEADERS)
        clock.tick(10)
        perf.mark(PerfKeys.HEADERS) // retry: final attempt wins
        val snap = perf.snapshot()
        assertEquals(5L, snap.marks[PerfKeys.RECV])
        assertEquals(25L, snap.marks[PerfKeys.HEADERS])
    }

    @Test
    fun `markOnce keeps the first value`() {
        val clock = FakeClock()
        val perf = TurnPerf { clock.now }
        clock.tick(7)
        perf.markOnce(PerfKeys.FIRST_BYTE)
        clock.tick(100)
        perf.markOnce(PerfKeys.FIRST_BYTE)
        assertEquals(7L, perf.snapshot().marks[PerfKeys.FIRST_BYTE])
    }

    @Test
    fun `hasMark reflects whether a stage has been recorded`() {
        val perf = TurnPerf { 0L }
        assertFalse(perf.hasMark(PerfKeys.FIRST_FRAME))
        perf.markOnce(PerfKeys.FIRST_FRAME)
        assertTrue(perf.hasMark(PerfKeys.FIRST_FRAME))
        assertFalse(perf.hasMark(PerfKeys.FIRST_DELTA))
    }

    @Test
    fun `counters sum and zero deltas are skipped`() {
        val perf = TurnPerf { 0L }
        perf.add(PerfKeys.FRAMES_OUT, 1)
        perf.add(PerfKeys.FRAMES_OUT, 2)
        perf.add(PerfKeys.RETRIES, 0)
        val snap = perf.snapshot()
        assertEquals(3L, snap.counters[PerfKeys.FRAMES_OUT])
        assertTrue(PerfKeys.RETRIES !in snap.counters)
    }

    @Test
    fun `unmeasured timings are absent and a measured zero retains its event kind`() {
        val perf = TurnPerf { 0L }
        val unmeasured = perf.snapshot()
        for (key in listOf(
            PerfKeys.UP_GAP_MAX_MS,
            PerfKeys.UP_CONTENT_GAP_MAX_MS,
            PerfKeys.UP_GAPS_2S,
            PerfKeys.UP_BLOCKED_MAX_MS,
            PerfKeys.OUT_HOLD_MAX_MS,
            PerfKeys.OUT_GAP_MAX_MS,
        )) {
            assertTrue(key !in unmeasured.counters, "unmeasured $key must be absent")
        }
        assertEquals(null, unmeasured.upstreamGapEnd)
        perf.maxCount(PerfKeys.UP_GAP_MAX_MS, 0, UpstreamGapEnd.TEXT_DELTA)
        assertEquals(0L, perf.snapshot().counters[PerfKeys.UP_GAP_MAX_MS])
        assertEquals(UpstreamGapEnd.TEXT_DELTA, perf.snapshot().upstreamGapEnd)
    }

    @Test
    fun `maxima keep the longest value and its event kind while snapshots remain immutable`() {
        val perf = TurnPerf { 0L }
        perf.maxCount(PerfKeys.UP_GAP_MAX_MS, 2_500, UpstreamGapEnd.THINKING_DELTA)
        val first = perf.snapshot()
        perf.maxCount(PerfKeys.UP_GAP_MAX_MS, 500, UpstreamGapEnd.PING)
        perf.maxCount(PerfKeys.UP_GAP_MAX_MS, 2_500, UpstreamGapEnd.TEXT_DELTA)
        assertEquals(first, perf.snapshot(), "shorter gaps and ties must not relabel the maximum")
        perf.maxCount(PerfKeys.OUT_HOLD_MAX_MS, 73)
        perf.maxCount(PerfKeys.UP_GAP_MAX_MS, 3_000, UpstreamGapEnd.INPUT_JSON_DELTA)
        assertEquals(2_500L, first.counters[PerfKeys.UP_GAP_MAX_MS])
        assertEquals(UpstreamGapEnd.THINKING_DELTA, first.upstreamGapEnd)
        assertEquals(3_000L, perf.snapshot().counters[PerfKeys.UP_GAP_MAX_MS])
        assertEquals(UpstreamGapEnd.INPUT_JSON_DELTA, perf.snapshot().upstreamGapEnd)
        assertEquals(73L, perf.snapshot().counters[PerfKeys.OUT_HOLD_MAX_MS])
    }

    @Test
    fun `timed attributes block duration to the counter and timedOr is a no-op on null`() = runTest {
        val clock = FakeClock()
        val perf = TurnPerf { clock.now }
        val result = perf.timed(PerfKeys.AUTH_MS) {
            clock.tick(42)
            "creds"
        }
        assertEquals("creds", result)
        assertEquals(42L, perf.snapshot().counters[PerfKeys.AUTH_MS])
        val plain = TurnPerfTiming.timedOr(null as TurnPerf?, PerfKeys.AUTH_MS) { "plain" }
        assertEquals("plain", plain)
    }

    @Test
    fun `perfLine renders marks in pipeline order then counters`() {
        val clock = FakeClock()
        val perf = TurnPerf { clock.now }
        clock.tick(3)
        perf.mark(PerfKeys.RECV)
        clock.tick(900)
        perf.mark(PerfKeys.HEADERS)
        clock.tick(1)
        perf.mark(PerfKeys.TOTAL)
        perf.add(PerfKeys.OUT_TOKENS, 850)
        val line = perf.snapshot().perfLine("codex", "ok", compact = false, model = "gpt-5.6-sol")
        assertEquals(
            "[codex] perf outcome=ok compact=false model=gpt-5.6-sol recv=3 headers=903 total=904 | " +
                "out_tokens=850\n",
            line,
        )
        val tagged = perf.snapshot().perfLine(
            "codex",
            "client_abort",
            compact = false,
            model = "m",
            session = "a6b15bd7",
        )
        val head = "[codex] perf outcome=client_abort compact=false model=m session=a6b15bd7 recv="
        assertTrue(tagged.startsWith(head), tagged)
    }
}
