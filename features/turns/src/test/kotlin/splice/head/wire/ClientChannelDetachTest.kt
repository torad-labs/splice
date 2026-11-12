// NEW (2026-09-05): a recording channel outlives its client — a failed write detaches it instead of
// failing the turn, and a channel without a recording still fails exactly as before.
package splice.head.wire

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.TurnPerf
import splice.core.util.ElapsedClock
import java.io.IOException
import java.io.Writer
import java.util.concurrent.atomic.AtomicBoolean

class ClientChannelDetachTest {

    private val clock = ElapsedClock { 0L }

    private fun channel(recording: FrameRecording?, writeRaw: (String) -> Unit) = ClientChannel(
        ImmediateSseWriter(writeRaw = { writeRaw(it) }, flushRaw = {}),
        Mutex(),
        AtomicBoolean(false),
        recording = recording,
    )

    @Test
    fun `a recording channel detaches on a failed write - later frames are recorded, not written, nothing thrown`() {
        val recording = FrameRecording()
        val written = mutableListOf<String>()
        var dead = false
        val ch = channel(recording) { if (dead) throw IOException("Broken pipe") else written += it }
        val perf = TurnPerf()
        ch.timedClientWrite("event: message_start\n\n", perf, clock)
        dead = true
        ch.timedClientWrite("event: content_block_delta\n\n", perf, clock) // fails: detaches
        ch.timedClientWrite("event: message_stop\n\n", perf, clock) // skipped on the socket, recorded
        assertTrue(ch.detached.get())
        assertTrue(ch.clientGone.get())
        assertEquals(1, written.size, "nothing reaches the socket after the detach")
        assertEquals(3, recording.size, "every frame is recorded, before and after")
    }

    @Test
    fun `a channel without a recording still throws and flips clientGone`() {
        val ch = channel(null) { throw IOException("Broken pipe") }
        assertThrows(IOException::class.java) { ch.timedClientWrite("event: ping\n\n", TurnPerf(), clock) }
        assertTrue(ch.clientGone.get())
        assertFalse(ch.detached.get())
        assertFalse(ch.detachIfRecording(), "no recording, no detach: the turn keeps failing on a lost client")
    }

    @Test
    fun `detachIfRecording is the one switch and is idempotent`() {
        val ch = channel(FrameRecording()) { }
        assertTrue(ch.detachIfRecording())
        assertTrue(ch.detachIfRecording())
        assertTrue(ch.detached.get())
        assertTrue(ch.clientGone.get())
    }

    @Test
    fun `a closed channel is client loss - a recording channel detaches, nothing thrown`() {
        val recording = FrameRecording()
        var dead = false
        val ch = channel(recording) { if (dead) error("Channel is already closed") }
        val perf = TurnPerf()
        ch.timedClientWrite("event: message_start\n\n", perf, clock)
        dead = true
        ch.timedClientWrite("event: content_block_delta\n\n", perf, clock) // ISE: detaches
        assertTrue(ch.clientGone.get())
        assertTrue(ch.detached.get())
        assertEquals(2, recording.size)
    }

    @Test
    fun `a closed channel without a recording still throws ISE and flips clientGone`() {
        val ch = channel(null) { error("Channel is already closed") }
        assertThrows(IllegalStateException::class.java) { ch.timedClientWrite("event: ping\n\n", TurnPerf(), clock) }
        assertTrue(ch.clientGone.get())
        assertFalse(ch.detached.get())
    }

    /** PR 468's loaded CI: once Ktor cancels a response (its client left), a write to that response throws a
     *  CancellationException, not an IOException (ktor-io's CloseToken keeps the cancellation's type). A compaction
     *  whose drive wrote a frame in that moment ended "without a terminal frame" and its retry paid a second upstream
     *  turn. The client is gone either way: the recording channel detaches, and the turn runs on. */
    @Test
    fun `a write to a response Ktor cancelled is client loss - a recording channel detaches, nothing thrown`() {
        val recording = FrameRecording()
        val response = cancelledResponse()
        val ch = channel(recording) { response.write(it); response.flush() }
        ch.timedClientWrite("event: content_block_delta\n\n", TurnPerf(), clock)
        ch.timedClientWrite("event: message_stop\n\n", TurnPerf(), clock)
        assertTrue(ch.clientGone.get())
        assertTrue(ch.detached.get())
        assertEquals(2, recording.size, "every frame is recorded for the retry")
    }

    @Test
    fun `a write to a response Ktor cancelled still cancels a turn without a recording, and flips clientGone`() {
        val response = cancelledResponse()
        val ch = channel(null) { response.write(it); response.flush() }
        assertThrows(CancellationException::class.java) { ch.timedClientWrite("event: ping\n\n", TurnPerf(), clock) }
        assertTrue(ch.clientGone.get())
        assertFalse(ch.detached.get())
    }

    /** The writer a response body opened, over a response channel Ktor then cancelled as its client left. */
    private fun cancelledResponse(): Writer {
        val channel = ByteChannel()
        val writer = channel.toOutputStream().writer()
        channel.cancel(CancellationException("the client left"))
        return writer
    }
}
