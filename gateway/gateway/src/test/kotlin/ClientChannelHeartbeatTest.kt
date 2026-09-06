// NEW (2026-09-06): the pinger's heartbeat — a real ping event after 15 silent ticks, a frame on the
// socket resets the count, and a heartbeat that fails is a lost client like a failed comment.
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.TurnPerf
import splice.core.util.ElapsedClock
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.ImmediateSseWriter
import splice.spi.Ticker
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class ClientChannelHeartbeatTest {

    private val clock = ElapsedClock { 0L }

    private fun channel(writeRaw: (String) -> Unit) = ClientChannel(
        ImmediateSseWriter(writeRaw = { writeRaw(it) }, flushRaw = {}),
        Mutex(),
        AtomicBoolean(false),
    )

    /** A ticker that paces exactly [ticks] ticks, then stops the pinger loop. */
    private class CountedTicker(private val ticks: Int) : Ticker {
        var elapsed = 0
        override suspend fun awaitTick(intervalMs: Long): Boolean {
            if (elapsed >= ticks) return false
            elapsed += 1
            return true
        }
    }

    @Test
    fun `fifteen silent ticks write one heartbeat, then the count starts over`() = runBlocking {
        val written = mutableListOf<String>()
        val ch = channel { written += it }
        var beats = 0
        val turnJob = Job()
        ch.launchClientPinger(this, turnJob, CountedTicker(30), "codex", {}, null) {
            beats += 1
            ch.timedClientWrite("event: ping\n\n", TurnPerf(), clock)
        }.join()
        assertEquals(2, beats, "ticks 15 and 30 are heartbeats")
        assertEquals(28, written.count { it.startsWith(": ping") }, "every other tick is the comment keepalive")
        assertTrue(turnJob.isActive)
    }

    @Test
    fun `a frame on the socket resets the silence, so a streaming turn never heartbeats`() = runBlocking {
        val written = mutableListOf<String>()
        val ch = channel { written += it }
        var beats = 0
        val ticker = object : Ticker {
            var n = 0
            override suspend fun awaitTick(intervalMs: Long): Boolean {
                if (n >= 40) return false
                n += 1
                // A content frame lands every 10 ticks: the wire is never silent for 15.
                if (n % 10 == 0) ch.timedClientWrite("event: content_block_delta\n\n", TurnPerf(), clock)
                return true
            }
        }
        ch.launchClientPinger(this, Job(), ticker, "codex", {}, null) { beats += 1 }.join()
        assertEquals(0, beats)
        assertEquals(40, written.count { it.startsWith(": ping") })
    }

    @Test
    fun `a heartbeat that fails on a dead client cancels the turn like a failed comment`() = runBlocking {
        var dead = false
        val ch = channel { if (dead) throw IOException("Broken pipe") }
        val turnJob = Job()
        val logs = mutableListOf<String>()
        ch.launchClientPinger(this, turnJob, CountedTicker(15), "codex", { logs += it }, null) {
            dead = true
            ch.timedClientWrite("event: ping\n\n", TurnPerf(), clock)
        }.join()
        assertTrue(ch.clientGone.get())
        assertTrue(turnJob.isCancelled, "the failed heartbeat cancels the turn: $logs")
    }
}
