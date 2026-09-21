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
import splice.upstream.transport.PostContext
import splice.upstream.transport.UpstreamFailed
import splice.upstream.transport.clientOver
import splice.upstream.transport.fakeAuth
import splice.upstream.transport.postOnce
import splice.upstream.transport.posted
import java.util.concurrent.atomic.AtomicInteger

class OverflowNotRetriedTest {

    private suspend fun sendsFor(body: String): Int {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond(body, HttpStatusCode.BadRequest, headersOf())
        }
        assertThrows<UpstreamFailed> { postOnce(clientOver(engine)) }
        return calls.get()
    }

    // Mutant: delete the carve-out in RetryRules.statusPlan. Both overflow cells go red — the
    // request is sent the whole budget over.
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
            """your messages resulted in 131072 tokens.","type":"invalid_request_error","code":"context_length_exceeded"}}"""

        assertEquals(1, sendsFor(openAi))
    }

    // V4-167. Mutant: read the overflow text before the status (V4-164). A per-minute token quota's
    // 429 says "too many tokens", read as an overflow: no retry, no cooldown armed for the account's
    // other turns, and the client told to compact a conversation that fits.
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
            onRetry = RetryNotice { notices += it },
        )
        assertThrows<Exception> { clientOver(engine).posted(ctx, "{}") { "ok" } }

        assertTrue(notices.isNotEmpty(), "the rate-limit path spoke")
        assertTrue(notices.none { "context overflow" in it }, "$notices")
    }

    // V4-62 is untouched for everything else: a 400 the classifier cannot name still takes the curve.
    @Test
    fun `any other 400 is still retried`() = runTest {
        assertTrue(sendsFor("nope") > 1)
    }
}
