// DR-21: the upstream-error-body leg was the one of three spurious-wakeup consolidation legs with
// no test — and its failure shape replaced a classified upstream STATUS with an unclassified
// IOException. The contract pinned here: a torn peer mid-error-body degrades to the truncated
// diagnostic text and the status the caller already holds stays the turn's signal. The channel
// double mirrors ChannelReadsTest's TornChannel: readBuffer always empty, awaitContent lies.
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.LimitedBodyReader

class LimitedBodyReaderTest {

    @OptIn(InternalAPI::class)
    private class TornChannel : ByteReadChannel {
        override val closedCause: Throwable? = null
        override val isClosedForRead: Boolean = false
        override val readBuffer: Source = Buffer() // always empty -> readAvailable returns 0
        override suspend fun awaitContent(min: Int): Boolean = true // lies: claims content, delivers none
        override fun cancel(cause: Throwable?) { /* test double: nothing to cancel */ }
    }

    @Test
    fun `a torn peer mid-error-body yields the truncated diagnostic not a thrown wakeup`() = runTest {
        val text = LimitedBodyReader().read(TornChannel(), maxBytes = 4096)
        assertTrue(text.contains("omitted"), "expected the truncated diagnostic, got '$text'")
    }
}
