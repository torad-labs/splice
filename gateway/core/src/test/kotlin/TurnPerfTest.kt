// NEW: TurnPerf contract — marks are elapsed-at-completion (fake clock), markOnce keeps the
// first value, counters sum, timed() attributes block duration, and perfLine renders marks in
// pipeline order with counters after the bar.
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.perfLine
import splice.core.perf.timedOr

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
    fun `timed attributes block duration to the counter and timedOr is a no-op on null`() = runTest {
        val clock = FakeClock()
        val perf = TurnPerf { clock.now }
        val result = perf.timed(PerfKeys.AUTH_MS) {
            clock.tick(42)
            "creds"
        }
        assertEquals("creds", result)
        assertEquals(42L, perf.snapshot().counters[PerfKeys.AUTH_MS])
        val plain = (null as TurnPerf?).timedOr(PerfKeys.AUTH_MS) { "plain" }
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
        val line = perfLine("codex", "ok", compact = false, model = "gpt-5.6-sol", snap = perf.snapshot())
        assertEquals(
            "[codex] perf outcome=ok compact=false model=gpt-5.6-sol recv=3 headers=903 total=904 | out_tokens=850\n",
            line,
        )
    }
}
