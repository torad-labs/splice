// DR-21 redo (codex production-wiring catch): LimitedBodyReaderTest pinned only the extracted
// helper, so re-inlining an uncaught ChannelReads walk in UpstreamResponse.bodyTextLimited itself
// stayed green. This drives the PRODUCTION method: a torn peer injected through the body-channel
// seam must degrade to the truncated diagnostic while the upstream STATUS the caller already holds
// (503) stays intact — never a thrown SseSpuriousWakeupException that erases the classified failure.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.BodyChannelSource
import splice.spi.UpstreamResponse

class UpstreamResponseTornBodyTest {

    // Mirrors ChannelReadsTest/LimitedBodyReaderTest: readBuffer always empty (readAvailable returns
    // 0) and awaitContent lies "true" — the storm ChannelReads.readAvailableOrEof turns into
    // SseSpuriousWakeupException after MAX_SPURIOUS_WAKEUPS.
    @OptIn(InternalAPI::class)
    private class TornChannel : ByteReadChannel {
        override val closedCause: Throwable? = null
        override val isClosedForRead: Boolean = false
        override val readBuffer: Source = Buffer()
        override suspend fun awaitContent(min: Int): Boolean = true
        override fun cancel(cause: Throwable?) { /* test double: nothing to cancel */ }
    }

    @Test
    fun `a torn error body through UpstreamResponse degrades to the diagnostic and keeps the status`() = runTest {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) })
        val resp: HttpResponse = client.get("http://upstream.invalid/")
        val upstream = UpstreamResponse(resp, BodyChannelSource { TornChannel() })

        val text = upstream.bodyTextLimited(4096)

        assertEquals(503, upstream.status, "the classified upstream status must survive a torn error body")
        assertTrue(text.contains("omitted"), "a torn peer must yield the truncated diagnostic, got '$text'")
        client.close()
    }
}
