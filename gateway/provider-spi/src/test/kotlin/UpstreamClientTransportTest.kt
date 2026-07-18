// NEW: transport-retry pins (kimi 07:00 burst, 2026-07-18: a ~2-minute DNS blip produced 37
// user-visible `error:unexpected` turns, attempts=1 on every one — the retry loop only handled
// HTTP-status failures, never thrown transport errors). Connection-phase DNS/connect failures now
// retry on the normal backoff budget and rethrow only when exhausted; non-transport exceptions
// still fail immediately; the retryable set is pinned by predicate tests. MockEngine — no network.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.spi.UpstreamClient
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.atomic.AtomicInteger

class UpstreamClientTransportTest {

    private val fakeAuth = object : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials? = Credentials.ApiKey("k", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "fake", emptyMap())
    }

    private fun clientOver(engine: MockEngine) = UpstreamClient(
        firstByteTimeoutMs = 5_000,
        totalTimeoutMs = 5_000,
        maxRetries = 3,
        client = HttpClient(engine),
        backoff = { /* no real sleep in tests */ },
    )

    @Test
    fun `dns failure retries and succeeds on a later attempt`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            if (calls.incrementAndGet() <= 2) throw UnresolvedAddressException()
            respond("ok-body", HttpStatusCode.OK, headersOf())
        }
        val retries = mutableListOf<String>()
        val out = clientOver(engine).post(
            url = "https://api.example.test/v1",
            bodyJson = "{}",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            onRetry = { retries.add(it) },
        ) { "reached-block" }
        assertEquals("reached-block", out)
        assertEquals(3, calls.get())
        assertEquals(2, retries.size)
        assertTrue(retries.all { it.startsWith("transport UnresolvedAddressException") })
    }

    @Test
    fun `persistent transport failure rethrows after the attempt budget`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            throw ConnectException("refused")
        }
        assertThrows<ConnectException> {
            clientOver(engine).post(
                url = "https://api.example.test/v1",
                bodyJson = "{}",
                auth = fakeAuth,
                extraHeaders = { emptyMap() },
            ) { "unreachable" }
        }
        assertEquals(3, calls.get()) // maxRetries attempts, then the real exception surfaces
    }

    @Test
    fun `non-transport exception fails immediately without retry`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            throw IllegalStateException("bug, not weather")
        }
        assertThrows<IllegalStateException> {
            clientOver(engine).post(
                url = "https://api.example.test/v1",
                bodyJson = "{}",
                auth = fakeAuth,
                extraHeaders = { emptyMap() },
            ) { "unreachable" }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `exception after the stream is handed to the block is never retried`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("body", HttpStatusCode.OK, headersOf())
        }
        assertThrows<ConnectException> {
            clientOver(engine).post(
                url = "https://api.example.test/v1",
                bodyJson = "{}",
                auth = fakeAuth,
                extraHeaders = { emptyMap() },
            ) { throw ConnectException("mid-stream reset") } // retryable TYPE, but block owns it
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `retryable predicate walks the cause chain and excludes cancellation`() {
        assertTrue(UpstreamClient.isRetryableTransport(UnresolvedAddressException()))
        assertTrue(UpstreamClient.isRetryableTransport(RuntimeException(ConnectException("wrapped"))))
        assertFalse(UpstreamClient.isRetryableTransport(IllegalStateException("plain")))
        assertFalse(UpstreamClient.isRetryableTransport(RuntimeException(RuntimeException("no io below"))))
    }
}
