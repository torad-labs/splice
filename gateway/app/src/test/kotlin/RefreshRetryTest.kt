// NEW (G7): pins refreshWithRetry/isTerminalRefreshFailure directly, with a trivial classify
// lambda (no kimi/grok/codex-specific parsing needed) — MockEngine, no network.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.RefreshRetry
import splice.app.RefreshStep
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class RefreshRetryTest {

    private val retry = RefreshRetry()

    private val json = Json { ignoreUnknownKeys = true }

    private fun clientOf(engine: MockEngine) = HttpClient(engine)

    private suspend fun call(client: HttpClient) = client.get("https://refresh.example.test/token")

    @Test
    fun `first attempt success returns Terminal value without retrying`() = runTest {
        val calls = AtomicInteger()
        val client = clientOf(
            MockEngine {
                calls.incrementAndGet()
                respond("ok", HttpStatusCode.OK, headersOf())
            },
        )
        val result = retry.refreshWithRetry(
            call = { call(client) },
            classify = { RefreshStep.Terminal("ok") },
        )
        assertEquals("ok", result)
        assertEquals(1, calls.get())
    }

    @Test
    fun `retryable classification retries then succeeds`() = runTest {
        val calls = AtomicInteger()
        val client = clientOf(
            MockEngine {
                calls.incrementAndGet()
                respond("body", HttpStatusCode.OK, headersOf())
            },
        )
        val result = retry.refreshWithRetry(
            call = { call(client) },
            classify = { if (calls.get() == 1) RefreshStep.Retry else RefreshStep.Terminal("ok") },
        )
        assertEquals("ok", result)
        assertEquals(2, calls.get())
    }

    @Test
    fun `terminal null short-circuits without further attempts`() = runTest {
        val calls = AtomicInteger()
        val client = clientOf(
            MockEngine {
                calls.incrementAndGet()
                respond("body", HttpStatusCode.OK, headersOf())
            },
        )
        val result = retry.refreshWithRetry<String>(
            call = { call(client) },
            classify = { RefreshStep.Terminal(null) },
        )
        assertNull(result)
        assertEquals(1, calls.get())
    }

    @Test
    fun `retries exhausted returns null after REFRESH_MAX_ATTEMPTS calls`() = runTest {
        val calls = AtomicInteger()
        val client = clientOf(
            MockEngine {
                calls.incrementAndGet()
                respond("body", HttpStatusCode.OK, headersOf())
            },
        )
        val result = retry.refreshWithRetry<String>(
            call = { call(client) },
            classify = { RefreshStep.Retry },
        )
        assertNull(result)
        assertEquals(3, calls.get())
    }

    @Test
    fun `a thrown exception during call is treated as retryable, not a permanent failure`() = runTest {
        val calls = AtomicInteger()
        val client = clientOf(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })
        val result = retry.refreshWithRetry(
            call = {
                if (calls.incrementAndGet() == 1) throw IOException("DNS blip") else call(client)
            },
            classify = { RefreshStep.Terminal("recovered") },
        )
        assertEquals("recovered", result)
        assertEquals(2, calls.get())
    }

    @Test
    fun `isTerminalRefreshFailure invalid_grant body wins even under a nominally-retryable status`() {
        assertTrue(retry.isTerminalRefreshFailure(500, """{"error":"invalid_grant"}""", json))
    }

    // DR-82 (assembly sweep): the loop's runCatching discarded the throwable, making
    // RefreshOutcome.TransportFailed unreachable from production — a network outage reported as
    // "refresh rejected by token endpoint". Exhaustion whose FINAL attempt threw now propagates
    // that throw to the provider boundary (whose existing getOrElse maps TransportFailed);
    // status-classified exhaustion still returns null, because there the endpoint really answered.
    @Test
    fun `exhaustion by transport failure propagates the final throw - DR-82`() = runTest {
        val boom = IOException("network unreachable")
        val calls = AtomicInteger()
        val quiet = RefreshRetry(waiter = splice.spi.Waiter { })
        val outcome = runCatching {
            quiet.refreshWithRetry(
                call = {
                    calls.incrementAndGet()
                    throw boom
                },
                classify = { RefreshStep.Terminal("never") },
            )
        }
        assertEquals(3, calls.get(), "all attempts spent before giving up")
        assertTrue(outcome.exceptionOrNull() === boom, "the real transport failure reaches the boundary")
    }

    @Test
    fun `exhaustion by status classification still returns null - DR-82 control`() = runTest {
        val client = clientOf(MockEngine { respond("busy", HttpStatusCode.ServiceUnavailable, headersOf()) })
        val quiet = RefreshRetry(waiter = splice.spi.Waiter { })
        assertNull(quiet.refreshWithRetry(call = { call(client) }, classify = { RefreshStep.Retry }))
    }
}
