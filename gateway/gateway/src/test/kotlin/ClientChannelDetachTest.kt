// NEW (2026-09-05): a recording channel outlives its client — a failed write detaches it instead of
// failing the turn, and a channel without a recording still fails exactly as before.
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.TurnPerf
import splice.core.util.ElapsedClock
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.FrameRecording
import splice.gateway.wire.ImmediateSseWriter
import java.io.IOException
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
}
