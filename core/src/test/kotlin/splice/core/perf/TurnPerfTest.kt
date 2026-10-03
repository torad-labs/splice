// NEW: TurnPerf contract — marks are elapsed-at-completion (fake clock), markOnce keeps the
// first value, counters sum, timed() attributes block duration, and perfLine renders marks in
// pipeline order with counters after the bar.
package splice.core.perf

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.ElapsedClock
import splice.core.util.WallClock

class TurnPerfTest {

    private class FakeClock(var now: Long = 1_000L) {
        fun tick(ms: Long) {
            now += ms
        }
    }

    @Test
    fun `upstream milestones retain measured zero and never reuse an earlier attempt`() {
        val clock = FakeClock()
        val perf = TurnPerf { clock.now }
        perf.recordArrival(clock.now - 40)
        val first = UpstreamAttemptTiming(perf)
        clock.tick(100)
        first.written()
        first.firstByte()
        assertEquals(140L, perf.snapshot().counters[PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS])
        assertEquals(0L, perf.snapshot().counters[PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS])
        val second = UpstreamAttemptTiming(perf)
        assertTrue(PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS !in perf.snapshot().counters)
        assertTrue(PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS !in perf.snapshot().counters)
        first.written()
        first.firstByte()
        assertTrue(PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS !in perf.snapshot().counters, "stale attempt callback")
        assertTrue(PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS !in perf.snapshot().counters, "stale attempt callback")
        clock.tick(200)
        second.written()
        clock.tick(30)
        second.firstByte()
        clock.tick(50)
        second.firstByte()
        assertEquals(340L, perf.snapshot().counters[PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS])
        assertEquals(30L, perf.snapshot().counters[PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS])
    }

    @Test
    fun `a response before request completion does not invent a post-write upstream wait`() {
        val clock = FakeClock()
        val perf = TurnPerf { clock.now }
        val attempt = UpstreamAttemptTiming(perf)
        clock.tick(10)
        attempt.firstByte()
        assertTrue(PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS !in perf.snapshot().counters)
        clock.tick(10)
        attempt.written()
        assertTrue(PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS !in perf.snapshot().counters)
    }

    @Test
    fun `websocket acceptance retains zero and a protocol change retires its callbacks`() {
        val clock = FakeClock()
        val perf = TurnPerf { clock.now }
        perf.recordArrival(clock.now - 40)
        val first = WsAttemptTiming(perf)
        first.sendAccepted()
        first.firstFragment()
        assertEquals(40L, perf.snapshot().counters[PerfKeys.ARRIVAL_TO_WS_SEND_ACCEPTED_MS])
        assertEquals(0L, perf.snapshot().counters[PerfKeys.WS_SEND_ACCEPTED_TO_FIRST_FRAGMENT_MS])
        val sse = UpstreamAttemptTiming(perf)
        first.sendAccepted()
        assertTrue(PerfKeys.ARRIVAL_TO_WS_SEND_ACCEPTED_MS !in perf.snapshot().counters)
        assertTrue(PerfKeys.WS_SEND_ACCEPTED_TO_FIRST_FRAGMENT_MS !in perf.snapshot().counters)
        clock.tick(10)
        sse.written()
        sse.firstByte()
        val last = WsAttemptTiming(perf)
        sse.written()
        sse.firstByte()
        last.sendAccepted()
        clock.tick(20)
        last.firstFragment()
        first.sendAccepted()
        assertEquals(50L, perf.snapshot().counters[PerfKeys.ARRIVAL_TO_WS_SEND_ACCEPTED_MS])
        assertEquals(20L, perf.snapshot().counters[PerfKeys.WS_SEND_ACCEPTED_TO_FIRST_FRAGMENT_MS])
        assertTrue(PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS !in perf.snapshot().counters)
        assertTrue(PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS !in perf.snapshot().counters)
    }

    @Test
    fun `an early websocket fragment in the same clock tick cannot become a measured zero wait`() {
        val clock = FakeClock()
        val perf = TurnPerf { clock.now }
        val attempt = WsAttemptTiming(perf)
        attempt.firstFragment()
        attempt.sendAccepted()
        clock.tick(30)
        attempt.firstFragment()
        assertEquals(0L, perf.snapshot().counters[PerfKeys.ARRIVAL_TO_WS_SEND_ACCEPTED_MS])
        assertTrue(PerfKeys.WS_SEND_ACCEPTED_TO_FIRST_FRAGMENT_MS !in perf.snapshot().counters)
    }

    @Test
    fun `header receipt keeps arrival origin zero and latest-attempt ownership`() {
        val clock = FakeClock()
        val perf = TurnPerf { clock.now }
        perf.recordArrival(clock.now - 30)
        val first = UpstreamAttemptTiming(perf)
        clock.tick(10)
        first.written()
        clock.tick(50)
        first.headersStarted()
        clock.tick(4)
        first.headersDelivered()
        clock.tick(20)
        first.firstByte()
        assertEquals(90L, perf.snapshot().counters[PerfKeys.ARRIVAL_TO_UPSTREAM_HEADERS_START_MS])
        assertEquals(4L, perf.snapshot().counters[PerfKeys.UPSTREAM_HEADERS_START_TO_KTOR_HEADERS_MS])
        assertEquals(74L, perf.snapshot().counters[PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS])
        val last = UpstreamAttemptTiming(perf)
        first.headersStarted()
        first.headersDelivered()
        assertTrue(PerfKeys.ARRIVAL_TO_UPSTREAM_HEADERS_START_MS !in perf.snapshot().counters)
        assertTrue(PerfKeys.UPSTREAM_HEADERS_START_TO_KTOR_HEADERS_MS !in perf.snapshot().counters)
        last.written()
        last.headersStarted()
        last.headersDelivered()
        assertEquals(114L, perf.snapshot().counters[PerfKeys.ARRIVAL_TO_UPSTREAM_HEADERS_START_MS])
        assertEquals(0L, perf.snapshot().counters[PerfKeys.UPSTREAM_HEADERS_START_TO_KTOR_HEADERS_MS])
        val ws = WsAttemptTiming(perf)
        last.headersDelivered()
        ws.sendAccepted()
        ws.firstFragment()
        assertTrue(PerfKeys.ARRIVAL_TO_UPSTREAM_HEADERS_START_MS !in perf.snapshot().counters)
        assertTrue(PerfKeys.UPSTREAM_HEADERS_START_TO_KTOR_HEADERS_MS !in perf.snapshot().counters)
    }

    @Test
    fun `the last OkHttp header event measures the final Ktor handoff`() {
        val clock = FakeClock()
        val perf = TurnPerf { clock.now }
        val timing = UpstreamAttemptTiming(perf)
        timing.written()
        clock.tick(10)
        timing.headersStarted()
        clock.tick(30)
        timing.headersStarted()
        clock.tick(2)
        timing.headersDelivered()
        assertEquals(40L, perf.snapshot().counters[PerfKeys.ARRIVAL_TO_UPSTREAM_HEADERS_START_MS])
        assertEquals(2L, perf.snapshot().counters[PerfKeys.UPSTREAM_HEADERS_START_TO_KTOR_HEADERS_MS])
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
    fun `epoch maxima keep zero ties and immutable intervals through a wall clock jump`() {
        var wall = 1_000_000L
        val perf = TurnPerf(ElapsedClock { 5_000 }, WallClock { wall })
        perf.recordArrival(4_000)
        perf.intervals.record(PerfKeys.OUT_HOLD_MAX_MS, 10, 10)
        assertEquals(0L, perf.snapshot().counters[PerfKeys.OUT_HOLD_MAX_MS])
        assertEquals(1_000_010L, perf.snapshot().counters[PerfKeys.OUT_HOLD_MAX_START_EPOCH_MS])
        perf.intervals.record(PerfKeys.UP_GAP_MAX_MS, 20, 70, UpstreamGapEnd.TEXT_DELTA)
        val first = perf.snapshot()
        wall = 9_000_000
        perf.intervals.record(PerfKeys.UP_GAP_MAX_MS, 100, 150, UpstreamGapEnd.PING)
        assertEquals(first, perf.snapshot(), "ties cannot move or relabel the interval")
        perf.intervals.record(PerfKeys.UP_GAP_MAX_MS, 200, 300, UpstreamGapEnd.THINKING_DELTA)
        assertEquals(1_000_200L, perf.snapshot().counters[PerfKeys.UP_GAP_MAX_START_EPOCH_MS])
        assertEquals(1_000_020L, first.counters[PerfKeys.UP_GAP_MAX_START_EPOCH_MS])
        perf.maxCount(PerfKeys.UP_GAP_MAX_MS, 200)
        assertTrue(PerfKeys.UP_GAP_MAX_START_EPOCH_MS !in perf.snapshot().counters, "untimed replacement has no epoch")
    }

    @Test
    fun `retired read callbacks cannot change the turn maximum and retries never join read gaps`() {
        val clock = FakeClock()
        val perf = TurnPerf(ElapsedClock { clock.now }, WallClock { 1_000_000 })
        val first = UpstreamAttemptTiming(perf)
        first.readStarted()
        clock.tick(10)
        first.firstByte()
        first.readStarted()
        clock.tick(20)
        first.firstByte()
        val before = perf.snapshot().counters[PerfKeys.UP_WIRE_GAP_MAX_START_EPOCH_MS]
        val current = WsAttemptTiming(perf)
        clock.tick(5_000)
        first.readStarted()
        first.firstByte()
        assertEquals(20L, perf.snapshot().counters[PerfKeys.UP_WIRE_GAP_MAX_MS])
        assertEquals(before, perf.snapshot().counters[PerfKeys.UP_WIRE_GAP_MAX_START_EPOCH_MS])
        current.requested(clock.now - 10_000)
        clock.tick(10)
        current.firstFragment()
        assertEquals(
            5_010L,
            perf.snapshot().counters[PerfKeys.UP_READ_WAIT_MAX_MS],
            "pooled demand is clipped to attempt start",
        )
        assertEquals(20L, perf.snapshot().counters[PerfKeys.UP_WIRE_GAP_MAX_MS], "the new attempt has one fragment")
    }

    @Test
    fun `binding an older WS demand cannot replace the demand already observed`() {
        val clock = FakeClock()
        val perf = TurnPerf(ElapsedClock { clock.now }, WallClock { 1_000_000 })
        val timing = WsAttemptTiming(perf)
        timing.requested(clock.now)
        clock.tick(10)
        timing.requested(clock.now)
        timing.requested(clock.now - 10)
        clock.tick(10)
        timing.firstFragment()
        assertEquals(10L, perf.snapshot().counters[PerfKeys.UP_READ_WAIT_MAX_MS])
        assertEquals(1_000_010L, perf.snapshot().counters[PerfKeys.UP_READ_WAIT_MAX_START_EPOCH_MS])
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
