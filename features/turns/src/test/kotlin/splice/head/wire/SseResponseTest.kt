// SseResponse's one guarantee, driven the way the Netty engine drives a response body
// (BaseApplicationResponse.respondWriteChannelContent in Ktor 3.5.2): a plain ByteChannel, the body
// run inside `channel.use { content.writeTo(this) }`, and a reader that is LATE — it starts only after
// the body has already thrown. That lateness is the whole bug: on a loaded host the engine's reader
// lags the writer, and a Ktor-cancelled channel drops what the reader has not taken yet. Here the lag
// is total, so each arm is deterministic instead of a load-dependent race.
//
// The Ktor arm is the premise, kept as a test rather than a comment: it shows the harness CAN see the
// loss (the SseResponse arm would pass vacuously on a harness that never drops anything), and it goes
// red the day a Ktor upgrade stops dropping, which is the day SseResponse's finally can be retired.
package splice.head.wire

import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.WriterContent
import io.ktor.util.cio.use
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SseResponseTest {

    // The seal's frame as the wall writes it: the last thing on the wire before the throw.
    private val errorFrame = "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"," +
        "\"message\":\"codex: upstream stalled (watchdog) — aborted; retry\"}}\n\n"
    private val startFrame = "event: message_start\ndata: {\"type\":\"message_start\"}\n\n"

    // Ktor's CHANNEL_MAX_SIZE is 1 MiB (internal): two halves fill the channel, one alone leaves room.
    private val halfOfChannel = 512 * 1024

    // What SealedDrive does at :42 — the frame is written and flushed, then the cancellation rethrows.
    private val sealThenRethrow = SseBody { out ->
        out.write(startFrame)
        out.flush()
        out.write(errorFrame)
        out.flush()
        throw CancellationException("turn cancelled by the whole-turn wall")
    }

    /** The engine's side: `use` around writeTo, then the reader, which starts only after the throw. */
    private suspend fun engineRun(content: OutgoingContent.WriteChannelContent): Pair<Throwable?, Result<String>> {
        val channel = ByteChannel()
        val thrown = runCatching { channel.use { content.writeTo(this) } }.exceptionOrNull()
        return thrown to runCatching { channel.toByteArray().decodeToString() }
    }

    @Test
    fun `a frame flushed before the body throws reaches a reader that starts after the throw`() = runTest {
        val (thrown, read) = engineRun(SseResponse(sealThenRethrow))

        assertEquals(startFrame + errorFrame, read.getOrThrow(), "every flushed frame is delivered, the seal's last")
        assertTrue(thrown is CancellationException, "the cancellation still propagates to the engine: $thrown")
    }

    @Test
    fun `Ktor's own writer drops the same frames - the loss this class exists for`() = runTest {
        val (thrown, read) = engineRun(WriterContent({ sealThenRethrow(this) }, SseResponse {}.contentType))

        assertTrue(thrown is CancellationException, "the body's cancellation reached the engine: $thrown")
        assertTrue(
            read.isFailure,
            "Ktor 3.5.2 cancels the channel and its reader throws before taking the flushed bytes. If this " +
                "arm reads the frames, the upgrade fixed the drop and SseResponse's finally can go: $read",
        )
    }

    @Test
    fun `a body that ends normally is delivered whole and closed clean`() = runTest {
        val (thrown, read) = engineRun(
            SseResponse { out ->
                out.write(startFrame)
                out.flush()
                out.write(errorFrame)
            },
        )

        assertNull(thrown)
        assertEquals(startFrame + errorFrame, read.getOrThrow(), "an unflushed tail is delivered by the close")
    }

    @Test
    fun `the response is the event stream respondTextWriter declared`() {
        assertEquals("text/event-stream; charset=UTF-8", SseResponse {}.contentType.toString())
    }

    // The path SseResponse's KDoc rests on when it refuses NonCancellable: flushAndClose from a Job
    // that is ALREADY cancelled, on a channel too full for its flush to return without suspending.
    // The suspension throws; the bytes must still be delivered and the close must still be clean, or
    // the engine's cancel(cause) that follows would drop them exactly as before.
    @Test
    fun `a cancelled job's flushAndClose on a full channel still closes clean and loses nothing`() = runTest {
        val channel = ByteChannel()
        val half = ByteArray(halfOfChannel) { 'x'.code.toByte() }
        channel.writeFully(half)
        channel.flush() // half the channel: flush returns at once
        channel.writeFully(half) // a second half, still unflushed
        channel.writeFully(errorFrame.encodeToByteArray())

        launch {
            cancel("the whole-turn wall")
            channel.flushAndClose() // moves 1 MiB + the frame across, so its flush must suspend — and throws
        }.join()

        assertTrue(channel.isClosedForWrite, "the close is set")
        assertNull(channel.closedCause, "and it is the clean close, not a cancellation")
        channel.cancel(CancellationException("what the engine's use {} does next")) // a no-op now
        val read = channel.toByteArray()
        assertEquals(2 * halfOfChannel + errorFrame.encodeToByteArray().size, read.size, "nothing is dropped")
        assertEquals(errorFrame, read.copyOfRange(2 * halfOfChannel, read.size).decodeToString())
    }
}
