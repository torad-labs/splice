// NEW: the walk that SseDecode already guarded is now shared with the request-body and
// upstream-error-body readers, which carried the PRE-fix shape (review 2026-08-28, PR 99 comments
// 2 and 6). SseReaderTest already pins the spurious-wakeup cap end to end THROUGH this primitive;
// what it cannot reach is the other guard, so the cancellation property is pinned here directly.
// The TornChannel double mirrors SseReaderTest's: readBuffer is always empty, so readAvailable
// returns 0, and awaitContent lies, so the loop never suspends. That is the degenerate peer of the
// 600%-CPU / "connection closed mid-response" incident, and the shape in which ensureActive() is
// the ONLY cancellation point that exists.
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.spi.ChannelReads
import splice.spi.SseSpuriousWakeupException

class ChannelReadsTest {

    @Test
    fun `a cancelled turn exits a torn channel instead of hot-spinning`() = runTest {
        val job = Job()
        var awaits = 0
        // Cancel from INSIDE the loop, once it is definitely spinning. Cancelling before the
        // coroutine starts proves nothing: it never enters the walk at all.
        val torn = TornChannel { if (++awaits == CANCEL_AFTER_AWAITS) job.cancel() }
        val running = CoroutineScope(coroutineContext + job)
            .async { ChannelReads.readAvailableOrEof(torn, ByteArray(16)) }
        withTimeout(TORN_CHANNEL_TIMEOUT_MS) {
            assertThrows<CancellationException> { running.await() }
        }
        // The discriminator. ensureActive() is the ONLY cancellation point in this loop, because a
        // torn channel's awaitContent returns without suspending. Remove that guard and the walk
        // ignores the cancelled job, runs all the way to the spurious-wakeup cap, and fails with
        // SseSpuriousWakeupException after 1024 awaits instead of stopping here.
        assertEquals(CANCEL_AFTER_AWAITS, awaits)
    }

    @Test
    fun `a torn channel terminates instead of looping forever`() = runTest {
        withTimeout(TORN_CHANNEL_TIMEOUT_MS) {
            assertThrows<SseSpuriousWakeupException> {
                ChannelReads.readAvailableOrEof(TornChannel(), ByteArray(16))
            }
        }
    }

    @Test
    fun `a healthy channel still reads its bytes and then reports EOF`() = runTest {
        val channel = ByteChannel()
        channel.writeFully("hello".toByteArray())
        channel.close()
        val buffer = ByteArray(16)
        assertEquals(5, ChannelReads.readAvailableOrEof(channel, buffer))
        assertEquals("hello", String(buffer, 0, 5))
        assertEquals(-1, ChannelReads.readAvailableOrEof(channel, buffer))
    }

    @OptIn(io.ktor.utils.io.InternalAPI::class)
    private class TornChannel(private val onAwait: () -> Unit = {}) : ByteReadChannel {
        override val closedCause: Throwable? = null
        override val isClosedForRead: Boolean = false
        override val readBuffer: Source = Buffer() // always empty -> readAvailable returns 0
        override suspend fun awaitContent(min: Int): Boolean {
            onAwait()
            return true // lies: claims content, delivers none
        }
        override fun cancel(cause: Throwable?) { /* test double: nothing to cancel */ }
    }

    private companion object {
        const val TORN_CHANNEL_TIMEOUT_MS = 5_000L
        const val CANCEL_AFTER_AWAITS = 5
    }
}
