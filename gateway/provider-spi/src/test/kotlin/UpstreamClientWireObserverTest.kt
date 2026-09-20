// NEW: V4-174 — the wire observer sits INSIDE the retry loop, so every SEND is its own record:
// the body exactly as encoded, the headers as assembled but with the credential redacted, the
// status and headers that came back or the transport failure that ended it, numbered in the order
// they left. Pinned against the mock engine's own request log, so "what the observer says was
// sent" is compared with what the transport was handed.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.spi.BodyAmendment
import splice.spi.HeaderRedaction
import splice.spi.PostContext
import splice.spi.UpstreamClient
import splice.spi.UpstreamFailed
import splice.spi.WireAttempt
import splice.spi.WireObserver
import java.io.IOException

class UpstreamClientWireObserverTest {

    private class Recording : WireObserver {
        val attempts = mutableListOf<WireAttempt>()
        override fun attempted(attempt: WireAttempt) {
            attempts += attempt
        }
    }

    private fun client(engine: MockEngine, maxRetries: Int = 3) = UpstreamClient(
        firstByteTimeoutMs = 5_000,
        totalTimeoutMs = 5_000,
        maxRetries = maxRetries,
        client = HttpClient(engine),
        backoff = { _, _ -> },
    )

    private fun context(wire: WireObserver, amend: BodyAmendment = BodyAmendment { _, _, _ -> null }) = PostContext(
        url = "https://api.example.test/v1/messages",
        auth = fakeAuth,
        extraHeaders = { mapOf("anthropic-version" to "2023-06-01", "X-Session-Token" to "sess-secret") },
        amendBodyOnFailure = amend,
        wire = wire,
    )

    @Test
    fun `one send, as it left and as it came back - credential redacted, body exact`() = runBlocking {
        val engine = MockEngine { respond("ok", HttpStatusCode.OK, headersOf("x-request-id", "req-7")) }
        val wire = Recording()

        client(engine).posted(context(wire), """{"model":"m","messages":[]}""") { "done" }

        val attempt = wire.attempts.single()
        assertEquals(1, attempt.attempt)
        assertEquals("https://api.example.test/v1/messages", attempt.url)
        assertEquals("""{"model":"m","messages":[]}""", attempt.requestBody)
        val sent = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertEquals("""{"model":"m","messages":[]}""", sent, "the transport was handed the same bytes")
        assertNull(attempt.requestEncoding)
        assertEquals(HeaderRedaction.REDACTED, attempt.requestHeaders["x-api-key"], "the credential never crosses")
        assertEquals(HeaderRedaction.REDACTED, attempt.requestHeaders["X-Session-Token"], "a token-named extra too")
        assertEquals("2023-06-01", attempt.requestHeaders["anthropic-version"], "a plain header rides whole")
        assertEquals(200, attempt.status)
        assertEquals("req-7", attempt.responseHeaders["x-request-id"])
        assertNull(attempt.errorText, "a 2xx body is the stream the caller consumed, not an error text")
        assertNull(attempt.failure)
        assertTrue(attempt.durationMs >= 0)
    }

    @Test
    fun `a retried send is a second record - the failed one keeps its status and error body`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls += 1
            if (calls == 1) respond("""{"error":"busy"}""", HttpStatusCode.ServiceUnavailable) else respond("ok")
        }
        val wire = Recording()

        client(engine).posted(context(wire), "{}") { "done" }

        assertEquals(listOf(1, 2), wire.attempts.map { it.attempt })
        assertEquals(503, wire.attempts[0].status)
        assertEquals("""{"error":"busy"}""", wire.attempts[0].errorText)
        assertEquals(200, wire.attempts[1].status)
        assertNull(wire.attempts[1].errorText)
    }

    @Test
    fun `the amended resend records the AMENDED body, not the round's first draft`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls += 1
            if (calls == 1) respond("""{"error":"stale"}""", HttpStatusCode.BadRequest) else respond("ok")
        }
        val wire = Recording()
        val amend = BodyAmendment { status, _, body -> if (status == 400) body.replace("v1", "v2") else null }

        client(engine).posted(context(wire, amend), """{"draft":"v1"}""") { "done" }

        assertEquals(listOf("""{"draft":"v1"}""", """{"draft":"v2"}"""), wire.attempts.map { it.requestBody })
        assertEquals(listOf(400, 200), wire.attempts.map { it.status })
    }

    @Test
    fun `a transport failure is a record with no status and the failure named`() {
        val engine = MockEngine { throw IOException("connection reset by peer") }
        val wire = Recording()

        assertThrows<IOException> {
            runBlocking { client(engine, maxRetries = 2).posted(context(wire), "{}") { "never" } }
        }

        assertEquals(listOf(1, 2), wire.attempts.map { it.attempt }, "every send, the retried one included")
        wire.attempts.forEach { attempt ->
            assertNull(attempt.status)
            assertEquals("IOException: connection reset by peer", attempt.failure)
            assertEquals("{}", attempt.requestBody)
        }
    }

    @Test
    fun `an exhausted retry budget still leaves every send on record`() {
        val engine = MockEngine { respond("no", HttpStatusCode.ServiceUnavailable) }
        val wire = Recording()

        assertThrows<UpstreamFailed> {
            runBlocking { client(engine, maxRetries = 2).posted(context(wire), "{}") { "never" } }
        }

        assertEquals(listOf(503, 503), wire.attempts.map { it.status })
    }
}
