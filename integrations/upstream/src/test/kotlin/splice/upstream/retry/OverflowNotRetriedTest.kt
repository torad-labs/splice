// NEW: V4-164 — a context overflow is sent once. The same bytes are the same token count against the
// same window, so a re-send cannot succeed; the remedy is the client's compaction, and every retry
// only delays it. Found by the row's own live proof: at upstreamRetries = 10 the bonsai head re-sent
// a 1.4 MB request ten times over 43 s, each one re-tokenized by llama-server, before Claude Code saw
// the "prompt is too long" line it compacts on.
package splice.upstream.retry

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.upstream.RetryNotice
import splice.upstream.failure.Replies
import splice.upstream.transport.PostContext
import splice.upstream.transport.PostObservers
import splice.upstream.transport.UpstreamFailed
import splice.upstream.transport.assertEnds
import splice.upstream.transport.clientOver
import splice.upstream.transport.fakeAuth
import splice.upstream.transport.postOnce
import splice.upstream.transport.posted
import java.util.concurrent.atomic.AtomicInteger
import splice.upstream.testing.OpenAiTestReplies as OpenAiReplies

class OverflowNotRetriedTest {

    private suspend fun sendsFor(body: String): Int {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond(body, HttpStatusCode.BadRequest, headersOf())
        }
        assertEnds<UpstreamFailed> { postOnce(clientOver(engine)) }
        return calls.get()
    }

    // An overflow is deterministic: resending it the whole budget over cannot heal it.
    @Test
    fun `llama-server's overflow is sent once`() = runTest {
        val llama = """{"error":{"code":400,"message":"request (270000 tokens) exceeds the available context """ +
            """size (262144 tokens), try increasing it","type":"exceed_context_size_error",""" +
            """"n_prompt_tokens":270000,"n_ctx":262144}}"""

        assertEquals(1, sendsFor(llama))
    }

    @Test
    fun `an OpenAI-shaped overflow is sent once`() = runTest {
        val openAi = """{"error":{"message":"This model's maximum context length is 128000 tokens. However, """ +
            """your messages resulted in 131072 tokens.","type":"invalid_request_error",""" +
            """"code":"context_length_exceeded"}}"""

        assertEquals(1, sendsFor(openAi))
    }

    // A per-minute token quota's 429 says "too many tokens". Read as an overflow it would mean no retry, no
    // cooldown for the account's other turns, and the client told to compact a conversation that fits.
    @Test
    fun `a rate limit that talks about tokens is a rate limit, not an overflow`() = runTest {
        val notices = mutableListOf<String>()
        val engine = MockEngine {
            respond(
                """{"error":{"message":"Tokens per minute limit exceeded - too many tokens processed.",""" +
                    """"type":"tokens"}}""",
                HttpStatusCode.TooManyRequests,
                headersOf(),
            )
        }
        val ctx = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
            observers = PostObservers(onRetry = RetryNotice { notices += it }),
        )
        assertThrows<Exception> { clientOver(engine).posted(ctx, "{}") { "ok" } }

        assertTrue(notices.isNotEmpty(), "the rate-limit path spoke")
        assertTrue(notices.none { "context overflow" in it }, "$notices")
    }

    // Failure-handling slice 3: any other 400 is the request rejected as sent (final-design.md section 7), so the
    // test that pinned V4-62's retry of a 400 the classifier could not name went with the classifier.
}

// The vendor validated the bytes and named the field: the same bytes fail the same way, so one attempt.
class BodyRejectionNotRetriedTest {

    private suspend fun sendsFor(body: String): Int {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond(body, HttpStatusCode.BadRequest, headersOf())
        }
        assertEnds<UpstreamFailed> { postOnce(clientOver(engine)) }
        return calls.get()
    }

    @Test
    fun `a 400 invalid_request_error naming a body field is sent once`() = runTest {
        val vendor = """{"type":"error","error":{"type":"invalid_request_error","message":""" +
            """"messages.342.content.0.thinking.estimated_tokens: Extra inputs are not permitted"},""" +
            """"request_id":"req_1"}"""

        assertEquals(1, sendsFor(vendor))
    }

    // Failure-handling slice 3: the reader reads every other 400 as the request rejected as sent, whether or not it
    // names a field, so the test that pinned V4-62's retry of a 400 naming none went with the classifier that told
    // the two apart.
}
