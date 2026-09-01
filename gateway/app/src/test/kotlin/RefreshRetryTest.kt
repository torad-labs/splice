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
import kotlin.coroutines.cancellation.CancellationException

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

    // DR-166 (found by codex-splice's test audit): swapping refreshWithRetry's
    // Cancellables.runCatchingCancellable for the stdlib runCatching left this whole suite green.
    // Production was already correct; nothing pinned it, so the guard was free to be deleted.
    //
    // The difference is not WHETHER a cancellation escapes — it does either way, through the final
    // rethrow — but WHEN. Caught as an ordinary failure it becomes RefreshStep.Retry, so a cancelled
    // turn spends all three attempts and sleeps TWO backoffs (~6s) inside a refresh nobody is
    // waiting for any more, and only then propagates. Distinct from DR-82 above, which pins a final
    // IOException reaching the provider boundary rather than a genuine cancellation short-circuiting
    // the loop; that arm cannot fail for this, which is why the swap survived it.
    @Test
    fun `a cancellation escapes at once, spending one call and no backoff - DR-166`() = runTest {
        val cancel = CancellationException("turn cancelled")
        val calls = AtomicInteger()
        val waits = mutableListOf<Long>()
        val recording = RefreshRetry(waiter = splice.spi.Waiter { ms -> waits += ms })
        val outcome = runCatching {
            recording.refreshWithRetry(
                call = {
                    calls.incrementAndGet()
                    throw cancel
                },
                classify = { RefreshStep.Terminal("never") },
            )
        }
        assertTrue(outcome.exceptionOrNull() === cancel, "the SAME cancellation instance must escape")
        assertEquals(1, calls.get(), "a cancelled refresh must not be retried")
        assertEquals(emptyList<Long>(), waits, "a cancelled refresh must never sleep on backoff")
    }

    // DR-166 control: the wait recorder actually records. Without this, the "no backoff" assertion
    // above would pass just as happily against an instrument that captures nothing — a green tick on
    // a measurement that was never taken.
    @Test
    fun `the backoff recorder does capture waits on a real retry - DR-166 control`() = runTest {
        val client = clientOf(MockEngine { respond("busy", HttpStatusCode.ServiceUnavailable, headersOf()) })
        val waits = mutableListOf<Long>()
        val recording = RefreshRetry(waiter = splice.spi.Waiter { ms -> waits += ms })
        assertNull(recording.refreshWithRetry(call = { call(client) }, classify = { RefreshStep.Retry }))
        assertEquals(2, waits.size, "three attempts sleep twice; the instrument sees them: $waits")
    }
}
