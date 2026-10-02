// NEW: V4-456 — a provider batch reaches the client paced, on the write path every head shares.
package splice.head.v4456

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.ElapsedClock
import splice.head.wire.ClientChannel
import splice.head.wire.DeltaPacer
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.LostClient
import splice.upstream.Ticker
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The JFR shape of Oct 1: 190 thinking deltas, about 31 KB, in one provider read, written in 3 ms. */
class PacedBurstTest {
    private val windowMs = 1_000L
    private val tickMs = 16L

    private class Time {
        var nowMs = 0L
        val clock = ElapsedClock { nowMs }
    }

    /** Advances the shared clock one tick per await, then lets the writer run. */
    private class FrameTicker(private val time: Time) : Ticker {
        override suspend fun awaitTick(intervalMs: Long): Boolean {
            time.nowMs += intervalMs
            yield()
            return true
        }
    }

    private data class Written(val frame: String, val atMs: Long)

    private val time = Time()
    private val written = mutableListOf<Written>()
    private var dead = false
    private val channel = ClientChannel(
        ImmediateSseWriter(
            writeRaw = {
                if (dead) throw IOException("Broken pipe")
                written += Written(it, time.nowMs)
            },
            flushRaw = {},
        ),
        Mutex(),
        AtomicBoolean(false),
    )
    private val perf = TurnPerf()

    private fun delta(i: Int, type: String = "thinking_delta", field: String = "thinking") =
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0," +
            "\"delta\":{\"type\":\"$type\",\"$field\":\"part $i of the summary \"}}\n\n"

    private fun structural(event: String) = "event: $event\ndata: {\"type\":\"$event\"}\n\n"

    private suspend fun write(frame: String) = channel.writeMutex.withLock {
        channel.timedClientWrite(frame, perf, time.clock)
    }

    private fun deltaTimes(): List<Long> = written.filter { it.frame.contains("_delta\"") }.map { it.atMs }

    @Test
    fun `successful client write gaps skip the first write and retain the longest interval`() = runBlocking {
        time.nowMs = 5_000
        write(structural("message_start"))
        assertNull(perf.snapshot().counters[PerfKeys.OUT_GAP_MAX_MS], "a single write has no inter-write interval")
        assertEquals(0L, perf.snapshot().counters[PerfKeys.OUT_HOLD_MAX_MS])
        time.nowMs += 73
        channel.writeMutex.withLock {
            channel.timedProgressWrite(structural("ping"), perf, time.clock)
        }
        time.nowMs += 16
        write(delta(0))
        assertEquals(73L, perf.snapshot().counters[PerfKeys.OUT_GAP_MAX_MS])
    }

    @Test
    fun `due and emergency drains record the exact longest residence of their held frames`() {
        val pacer = DeltaPacer().also { it.active = true }
        assertTrue(!pacer.hold(delta(0), perf, true, 0))
        assertTrue(pacer.hold(delta(1), perf, true, 1))
        assertTrue(pacer.hold(structural("content_block_stop"), perf, false, 2))
        assertEquals(2, pacer.due(42).size)
        assertEquals(41L, perf.snapshot().counters[PerfKeys.OUT_HOLD_MAX_MS])
        assertTrue(pacer.hold(delta(2), perf, true, 43))
        assertEquals(1, pacer.takeAll(116).size)
        assertEquals(73L, perf.snapshot().counters[PerfKeys.OUT_HOLD_MAX_MS])
    }

    @Test
    fun `finishing a paced turn records its hold and successful client write gaps`() = runBlocking {
        val pacing = channel.launchPacer(this, Job(), FrameTicker(time), time.clock, LostClient("synthetic", {}))
        write(delta(0))
        write(delta(1))
        channel.finishPacing(pacing, time.clock)
        assertEquals(16L, perf.snapshot().counters[PerfKeys.OUT_HOLD_MAX_MS])
        assertEquals(16L, perf.snapshot().counters[PerfKeys.OUT_GAP_MAX_MS])
        time.nowMs = 100
        write(structural("message_stop"))
        assertEquals(84L, perf.snapshot().counters[PerfKeys.OUT_GAP_MAX_MS])
        assertEquals(16L, perf.snapshot().counters[PerfKeys.OUT_HOLD_MAX_MS])
    }

    @Test
    fun `finishing after the release loop stops measures the emergency tail hold`() = runBlocking {
        val stopped = object : Ticker {
            override suspend fun awaitTick(intervalMs: Long): Boolean = false
        }
        val pacing = channel.launchPacer(this, Job(), stopped, time.clock, LostClient("synthetic", {}))
        write(delta(0))
        write(delta(1))
        pacing.join()
        time.nowMs = 73
        channel.finishPacing(pacing, time.clock)
        assertEquals(2, written.size)
        assertEquals(73L, perf.snapshot().counters[PerfKeys.OUT_HOLD_MAX_MS])
        assertEquals(73L, perf.snapshot().counters[PerfKeys.OUT_GAP_MAX_MS])
    }

    @Test
    fun `a 190 delta burst written in 3 ms reaches the client spread over the pacing window`() = runBlocking {
        val turn = Job()
        val pacing = channel.launchPacer(this, turn, FrameTicker(time), time.clock, LostClient("claude-splice", {}))
        repeat(190) { i ->
            time.nowMs = i / 64L
            write(delta(i))
        }
        assertEquals(1, deltaTimes().size, "only the first delta of the batch goes at once")
        assertEquals(190L, perf.snapshot().counters[PerfKeys.CONTENT_FRAMES_OUT], "a held delta is committed content")
        channel.finishPacing(pacing, time.clock)

        val times = deltaTimes()
        assertEquals(190, times.size)
        val perTick = times.groupingBy { it }.eachCount().values
        assertTrue(perTick.max() <= 4, "at most four deltas a tick, got ${perTick.max()}")
        assertTrue(times.last() >= windowMs / 2, "the batch is spread, not dumped: it ended at ${times.last()} ms")
        assertTrue(times.last() <= windowMs + tickMs, "and it ends inside the window: ${times.last()} ms")
        assertTrue(turn.isActive)
    }

    @Test
    fun `a lone delta after a quiet stretch and a token-paced stream are never delayed`() = runBlocking {
        val pacing = channel.launchPacer(this, Job(), FrameTicker(time), time.clock, LostClient("codex", {}))
        write(structural("message_start"))
        write(structural("content_block_start"))
        repeat(20) { i ->
            time.nowMs += 20
            write(delta(i, "text_delta", "text"))
            assertEquals(time.nowMs, written.last().atMs, "delta $i waited")
        }
        val end = time.nowMs
        channel.finishPacing(pacing, time.clock)
        assertEquals(22, written.size)
        assertEquals(end, time.nowMs, "nothing held: the turn ends without waiting a tick")
    }

    @Test
    fun `a block stop, a tool call and message_stop never pass a held delta`() = runBlocking {
        val pacing = channel.launchPacer(this, Job(), FrameTicker(time), time.clock, LostClient("claude-splice", {}))
        val sent = buildList {
            add(structural("message_start"))
            add(structural("content_block_start"))
            repeat(60) { add(delta(it)) }
            add(structural("content_block_stop"))
            add(structural("content_block_start"))
            add(delta(60, "input_json_delta", "partial_json"))
            add(structural("content_block_stop"))
            add(structural("message_delta"))
            add(structural("message_stop"))
        }
        sent.forEach { write(it) }
        assertTrue(written.none { it.frame.startsWith("event: message_stop") }, "the stop waits behind the held deltas")
        channel.finishPacing(pacing, time.clock)

        assertEquals(sent, written.map { it.frame }, "the wire order is the order the frames were written in")
        val lastDelta = written.indexOfLast { it.frame.contains("thinking_delta") }
        assertTrue(written.last().atMs >= written[lastDelta].atMs)
    }

    @Test
    fun `a stream faster than the floor is lagged by at most the window plus a tick`() = runBlocking {
        val wroteAt = HashMap<String, Long>()
        val pacing = channel.launchPacer(this, Job(), FrameTicker(time), time.clock, LostClient("codex", {}))
        // Twenty deltas a tick for two seconds: five times the floor, so the catch-up gear has to hold.
        val producer = launch {
            var n = 0
            repeat((2 * windowMs / tickMs).toInt()) {
                repeat(20) {
                    val frame = delta(n++, "text_delta", "text")
                    wroteAt[frame] = time.nowMs
                    write(frame)
                }
                yield()
            }
        }
        producer.join()
        channel.finishPacing(pacing, time.clock)

        val lags = written.map { it.atMs - wroteAt.getValue(it.frame) }
        assertEquals(wroteAt.size, written.size)
        assertTrue(lags.max() <= windowMs + tickMs, "max added lag ${lags.max()} ms")
    }

    @Test
    fun `a release that finds the client gone cancels the turn like a failed keepalive`() = runBlocking {
        val turn = Job()
        val logs = mutableListOf<String>()
        val lost = LostClient("claude-splice", { logs += it })
        val pacing = channel.launchPacer(this, turn, FrameTicker(time), time.clock, lost)
        repeat(40) { write(delta(it)) }
        dead = true
        // Bounded: a pacer that never holds would leave this loop parked on its signal for good.
        withTimeout(5_000) { pacing.join() }
        assertTrue(channel.clientGone.get())
        assertTrue(turn.isCancelled, "the paced write's failure cancels the turn: $logs")
        assertTrue(logs.single().contains("paced delta"), logs.toString())
        channel.finishPacing(pacing, time.clock)
    }
}
