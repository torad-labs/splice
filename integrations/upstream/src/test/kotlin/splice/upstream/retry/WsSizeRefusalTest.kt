// NEW: a body the WebSocket peer refused as too large is refused over HTTP once, as an overflow the client compacts on.
package splice.upstream.retry

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause
import splice.upstream.failure.FailureSource
import splice.upstream.failure.UpstreamFailureClassifier
import splice.upstream.transport.PostContext
import splice.upstream.transport.UpstreamFailed
import splice.upstream.transport.assertEnds
import splice.upstream.transport.clientOver
import splice.upstream.transport.fakeAuth
import splice.upstream.transport.posted
import java.util.concurrent.atomic.AtomicInteger

/**
 * Oct 4, 6:31 AM CT onward: 42 rounds of one claudex session were closed by the Responses WebSocket peer with 1009
 * (RFC 6455: message too big) before their first event, and every one then rode SSE and drew HTTP 400
 * `{"detail":"Bad Request"}` for the same body. V4-62 sent each of those four times, and the turn reached Claude
 * Code as an overload it retried. The 1009 is the evidence the 400 cannot carry: after it, the 400 is the second
 * refusal of the same bytes, sent once and named as an overflow so the client compacts.
 *
 * The bare 400 alone is not that evidence. On Oct 2 a 430 KB claudex body drew the same `{"detail":"Bad Request"}`
 * with no WebSocket refusal before it and succeeded on the next attempt, so V4-62 keeps retrying it.
 */
class WsSizeRefusalTest {
    private val badRequest = """{"detail":"Bad Request"}"""

    private data class Sent(val calls: Int, val failure: UpstreamFailed)

    private suspend fun send(status: HttpStatusCode, sizeRefused: Boolean, body: String = badRequest): Sent {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond(body, status, headersOf())
        }
        val ctx = PostContext(url = "https://api.example.test/v1", auth = fakeAuth, extraHeaders = { emptyMap() })
        ctx.bodyRefusedAsTooLarge = sizeRefused
        val failure = assertEnds<UpstreamFailed> { clientOver(engine).posted(ctx, "{}") { "ok" } }
        return Sent(calls.get(), failure)
    }

    @Test
    fun `a 400 for a body the WebSocket peer refused as too large is sent once and reads as prompt is too long`() =
        runTest {
            val sent = send(HttpStatusCode.BadRequest, sizeRefused = true)
            val read = UpstreamFailureClassifier.classify(FailureSource.HTTP, sent.failure.body, sent.failure.status)

            assertEquals(1, sent.calls, "the same bytes are refused again; a re-send only delays the compaction")
            assertEquals(FailureCause.REQUEST_TOO_LARGE, read.cause)
            assertEquals(ErrorType.INVALID_REQUEST, read.type)
            assertFalse(read.transient, "the client must not retry it")
            assertTrue(read.message.startsWith("prompt is too long"), read.message)
            assertEquals(400, sent.failure.status, "the upstream's status stays the turn's")
        }

    @Test
    fun `the same 400 with no WebSocket refusal before it keeps its retries`() = runTest {
        val sent = send(HttpStatusCode.BadRequest, sizeRefused = false)
        val read = UpstreamFailureClassifier.classify(FailureSource.HTTP, sent.failure.body, sent.failure.status)

        assertTrue(sent.calls > 1, "calls=${sent.calls}")
        assertEquals(FailureCause.UPSTREAM_STATUS_4XX, read.cause)
    }

    /** A rate limit heals with time and owns the cooldown, and a 5xx is the server's own failure: neither says
     *  anything about the body's size, so the WebSocket refusal before them changes nothing. */
    @ParameterizedTest
    @ValueSource(ints = [429, 500])
    fun `a refusal that is not about the body keeps its own path after a WebSocket size refusal`(code: Int) = runTest {
        val sent = send(HttpStatusCode.fromValue(code), sizeRefused = true, body = """{"error":{"message":"busy"}}""")
        val read = UpstreamFailureClassifier.classify(FailureSource.HTTP, sent.failure.body, sent.failure.status)

        assertTrue(read.cause != FailureCause.REQUEST_TOO_LARGE, "status $code read as $read")
        if (code == 500) assertTrue(sent.calls > 1, "a 5xx is retried: calls=${sent.calls}")
    }

    @Test
    fun `an upstream's own overflow text after a WebSocket size refusal reaches the client as the upstream wrote it`() =
        runTest {
            val own = """{"error":{"message":"Your input exceeds the context window of this model.",""" +
                """"type":"invalid_request_error","code":"context_length_exceeded"}}"""
            val sent = send(HttpStatusCode.BadRequest, sizeRefused = true, body = own)

            assertEquals(1, sent.calls)
            assertEquals(own, sent.failure.body)
        }
}
