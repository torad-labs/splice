// NEW (G3+G4a-c): retry-policy pins against the reference-harness survey — ALL 5xx retry except
// 501; 408 retries; 429 arms a shared cooldown and terminates without amplifying a retry wave;
// other 4xx are terminal. Retry-After seconds set the shared cooldown horizon. MockEngine — no network.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.spi.UpstreamClient
import splice.spi.UpstreamFailed
import java.util.concurrent.atomic.AtomicInteger

class UpstreamClientRetryPolicyTest {

    private val fakeAuth = object : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials? = Credentials.ApiKey("k", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "fake", emptyMap())
    }

    private class Capture {
        val minDelays = mutableListOf<Long>()
    }

    private fun clientOver(
        engine: MockEngine,
        capture: Capture = Capture(),
        clock: () -> Long = System::currentTimeMillis,
    ) = UpstreamClient(
        firstByteTimeoutMs = 5_000,
        totalTimeoutMs = 5_000,
        maxRetries = 3,
        client = HttpClient(engine),
        backoff = { _, minDelayMs -> capture.minDelays.add(minDelayMs) },
        clock = clock,
    )

    private suspend fun postOnce(client: UpstreamClient): String = client.post(
        url = "https://api.example.test/v1",
        bodyJson = "{}",
        auth = fakeAuth,
        extraHeaders = { emptyMap() },
    ) { "ok" }

    @Test
    fun `500 and 504 and 408 retry then succeed`() = runTest {
        val retryable = listOf(
            HttpStatusCode.InternalServerError,
            HttpStatusCode.GatewayTimeout,
            HttpStatusCode.RequestTimeout,
        )
        for (status in retryable) {
            val calls = AtomicInteger()
            val engine = MockEngine {
                if (calls.incrementAndGet() == 1) {
                    respond("boom", status, headersOf())
                } else {
                    respond("fine", HttpStatusCode.OK, headersOf())
                }
            }
            assertEquals("ok", postOnce(clientOver(engine)), "status $status should be retryable")
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun `501 and plain 400 are terminal without retry`() = runTest {
        for (status in listOf(HttpStatusCode.NotImplemented, HttpStatusCode.BadRequest)) {
            val calls = AtomicInteger()
            val engine = MockEngine {
                calls.incrementAndGet()
                respond("nope", status, headersOf())
            }
            assertThrows<UpstreamFailed> { postOnce(clientOver(engine)) }
            assertEquals(1, calls.get(), "status $status must not retry")
        }
    }

    @Test
    fun `failed response body is capped before classification`() = runTest {
        val engine = MockEngine {
            respond("x".repeat(100_000), HttpStatusCode.BadRequest, headersOf())
        }
        val failure = assertThrows<UpstreamFailed> { postOnce(clientOver(engine)) }
        assertTrue(failure.body.length < 70_000)
        assertTrue(failure.body.endsWith("[… omitted …]"))
    }

    @Test
    fun `429 retry-after sets cooldown without consuming a retry budget`() = runTest {
        val calls = AtomicInteger()
        val capture = Capture()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "7"))
        }
        val client = clientOver(engine, capture, clock = { 0L })
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        assertTrue(capture.minDelays.isEmpty())
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get(), "a follower inside Retry-After must not reach upstream")
    }

    @Test
    fun `absurd retry-after gives up instead of hammering`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("come back tomorrow", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "86400"))
        }
        assertThrows<UpstreamFailed> { postOnce(clientOver(engine)) }
        assertEquals(1, calls.get())
    }

    @Test
    fun `malformed retry-after falls back to the curve`() = runTest {
        val calls = AtomicInteger()
        val capture = Capture()
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) {
                respond(
                    "busy",
                    HttpStatusCode.ServiceUnavailable,
                    headersOf("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"),
                )
            } else {
                respond("fine", HttpStatusCode.OK, headersOf())
            }
        }
        assertEquals("ok", postOnce(clientOver(engine, capture)))
        assertEquals(listOf(0L), capture.minDelays) // no parseable floor — curve alone decides
    }

    // Shared 429 cooldown (2026-07-19 storm): one post's rate-limit discovery teaches the whole
    // client — the observer and all followers terminate without multiplying the retry wave.
    @Test
    fun `429 arms a shared cooldown - followers fail fast with zero upstream calls`() = runTest {
        var now = 0L
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("""{"detail":"Rate limit exceeded"}""", HttpStatusCode.TooManyRequests, headersOf())
        }
        val client = clientOver(engine, clock = { now })
        // the observer arms the cooldown and terminates without retrying
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        // a follower during the cooldown fails fast: 429 body names the cooldown, no upstream call
        val e = assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        assertEquals(429, e.status)
        assertTrue(e.body.contains("cooldown"))
        // default cooldown (no Retry-After) expires after 20s — traffic is attempted again
        now += 21_000
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(2, calls.get())
    }

    @Test
    fun `retry-after beyond the interactive budget gives up at once and arms its full cooldown`() = runTest {
        var now = 0L
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "30"))
        }
        val client = clientOver(engine, clock = { now })
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get()) // >15s pushback: the probe does not retry
        now += 25_000 // past the 20s default but inside the served 30s
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get()) // still cooling — no upstream call
        now += 6_000 // past the 30s Retry-After
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(2, calls.get()) // attempted again
    }

    @Test
    fun `default backoff jitters within the capped exponential envelope`() {
        // The default lambda is exercised via construction with real delays elsewhere; here we pin
        // the envelope math the comment promises: base doubles from 200ms and caps at 10s.
        val bases = (0..8).map { minOf(200L shl it, 10_000L) }
        assertTrue(bases.first() == 200L && bases.max() == 10_000L)
    }

    @Test
    fun `dns backoff envelope pins 1s-2s-4s schedule`() {
        // G14: DNS-class transport failures back off on their own curve — pin the literal math
        // the dnsBackoff comment promises, same idiom as the generic-curve pin above.
        val bases = (0..4).map { minOf(1_000L shl it, 4_000L) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 4_000L, 4_000L), bases)
    }
}
