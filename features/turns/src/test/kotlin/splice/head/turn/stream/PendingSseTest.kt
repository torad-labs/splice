// NEW: V4-446 — stream status is one decision, with only delivered frames counted.
package splice.head.turn.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.ElapsedClock
import splice.head.wire.FrameRecording
import java.io.IOException
import java.io.StringWriter
import java.io.Writer

class PendingSseTest {
    private fun gate(holdMs: Long = 1L): PendingSse = PendingSse(
        TurnPerf { 0L },
        ElapsedClock { 0L },
        null,
        null,
        holdMs,
    )

    private fun overflowFrame(message: String): String = "event: error\ndata: " +
        """{"type":"error","error":{"type":"invalid_request_error","message":"$message"}}""" +
        "\n\n"

    @Test
    fun `upstream size error before commitment discards the undelivered opening`() = runTest {
        val perf = TurnPerf { 0L }
        val pending = PendingSse(perf, ElapsedClock { 0L }, null, null, 1L)
        pending.model("event: message_start\ndata: {}\n\n")
        pending.model("event: ping\ndata: {}\n\n")
        pending.progress(": ping\n\n")
        pending.model(overflowFrame("prompt is too long: 210000 tokens > 200000 maximum"))
        val selected = pending.decide()
        assertTrue(selected is PendingSse.Decision.Overflow)
        assertTrue((selected as PendingSse.Decision.Overflow).body.contains("prompt is too long"))
        assertFalse(pending.channel.socketFrames.get() > 0, "staged opener did not reach a client")
        assertEquals(null, perf.snapshot().marks[PerfKeys.FIRST_FRAME])
        assertEquals(0L, perf.snapshot().counters[PerfKeys.FRAMES_OUT] ?: 0L)
    }

    @Test
    fun `a silent deadline commits SSE and a later size error stays in band`() = runTest {
        val pending = gate()
        pending.model("event: message_start\ndata: {}\n\n")
        assertEquals(PendingSse.Decision.Stream, pending.decide())
        val writer = StringWriter()
        pending.attach(writer)
        pending.model(overflowFrame("prompt is too long"))
        assertTrue(writer.toString().contains("event: message_start"))
        assertTrue(writer.toString().contains("event: error"))
    }

    @Test
    fun `a first model frame commits before the silent deadline`() = runTest {
        val perf = TurnPerf { 0L }
        val pending = PendingSse(perf, ElapsedClock { 0L }, null, null, 120_000L)
        pending.model("event: message_start\ndata: {}\n\n")
        val firstModelFrame = async { pending.model("event: content_block_start\ndata: {}\n\n") }
        assertEquals(PendingSse.Decision.Stream, pending.decide())
        val writer = StringWriter()
        pending.attach(writer)
        firstModelFrame.await()
        assertTrue(writer.toString().indexOf("message_start") < writer.toString().indexOf("content_block_start"))
        assertEquals(2L, perf.snapshot().counters[PerfKeys.FRAMES_OUT])
    }

    @Test
    fun `a cancelled compaction records its opener and first model frame`() = runTest {
        val perf = TurnPerf { 0L }
        val recording = FrameRecording()
        val pending = PendingSse(perf, ElapsedClock { 0L }, null, recording, 120_000L)
        val opener = "event: message_start\ndata: {}\n\n"
        val first = "event: content_block_start\ndata: {}\n\n"
        pending.model(opener)
        val writing = async { pending.model(first) }
        assertEquals(PendingSse.Decision.Stream, pending.decide())
        assertTrue(pending.detachForRecording())
        writing.await()
        assertEquals(listOf(opener, first), recording.frames())
        assertEquals(0L, pending.channel.socketFrames.get())
    }

    @Test
    fun `client cancellation before headers releases the unwritable model frame`() = runTest {
        val pending = gate(120_000L)
        pending.model("event: message_start\ndata: {}\n\n")
        val writing = async {
            try {
                pending.model("event: content_block_start\ndata: {}\n\n")
                false
            } catch (_: IOException) {
                true
            }
        }
        assertEquals(PendingSse.Decision.Stream, pending.decide())
        pending.abortClient()
        assertTrue(pending.channel.clientGone.get())
        assertTrue(withTimeout(1_000) { writing.await() })
        assertEquals(0L, pending.channel.socketFrames.get())
    }

    @Test
    fun `cancelled attachment records the staged remainder before later model frames`() = runTest {
        val recording = FrameRecording()
        val pending = PendingSse(TurnPerf { 0L }, ElapsedClock { 0L }, null, recording, 120_000L)
        val opener = "event: message_start\ndata: {}\n\n"
        val ping = "event: ping\ndata: {}\n\n"
        val progress = ": progress\n\n"
        val first = "event: content_block_start\ndata: {}\n\n"
        pending.model(opener)
        pending.model(ping)
        pending.progress(progress)
        val writing = async { pending.model(first) }
        assertEquals(PendingSse.Decision.Stream, pending.decide())
        val broken = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int): Unit =
                throw CancellationException("client cancelled during opener")
            override fun flush() = Unit
            override fun close() = Unit
        }
        try {
            pending.attach(broken)
        } catch (_: CancellationException) {
            // Cancellation is the reproduction; the detached drive must still receive all frames.
        }
        assertTrue(pending.detachForRecording())
        writing.await()
        assertEquals(listOf(opener, ping, progress, first), recording.frames())
    }

    @Test
    fun `a failed staged opener unblocks the model frame awaiting attachment`() = runTest {
        val pending = gate()
        pending.model("event: message_start\ndata: {}\n\n")
        pending.finish()
        val broken = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int): Unit = throw IOException("closed")
            override fun flush() = Unit
            override fun close() = Unit
        }
        val attachFailed = try {
            pending.attach(broken)
            false
        } catch (_: IOException) {
            true
        }
        assertTrue(attachFailed)
        val modelUnblocked = try {
            withTimeout(1_000) { pending.model("event: content_block_start\ndata: {}\n\n") }
            false
        } catch (_: IOException) {
            true
        }
        assertTrue(modelUnblocked, "a failed opener cannot strand the upstream drive")
    }
}
