// NEW: V4-63 split of UpstreamClientRetryPolicyTest.kt, the status family. Pure move: every
// test below is byte-identical to its original, same name, no assertion changes. Helpers shared with
// the sibling classes live in UpstreamClientRetryFixture.kt.
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
import splice.spi.AuthRefreshObserver
import splice.spi.PostContext
import splice.spi.UpstreamFailed
import java.util.concurrent.atomic.AtomicInteger

class UpstreamClientStatusTest {

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
    fun `every failure status retries, so 501 and plain 400 are not terminal on the first try`() = runTest {
        // V4-62, operator law: "we would retry on any error, no matter what, with different levels of
        // retry and escalation + backoff." These two statuses were exactly what the previous version
        // of this test pinned as terminal. They now take the same 200ms curve as a 503, and the
        // BUDGET is what ends the turn — never the status code.
        //
        // The cost argument is in RetryPolicy: the whole default budget on that curve is about 1.5s,
        // so a genuinely permanent 4xx is cheap to discover and a misclassified transient — a 403
        // observed as overload, the muse 400 that was OUR bug — is expensive to miss.
        for (status in listOf(HttpStatusCode.NotImplemented, HttpStatusCode.BadRequest)) {
            val calls = AtomicInteger()
            val engine = MockEngine {
                calls.incrementAndGet()
                respond("nope", status, headersOf())
            }
            assertThrows<UpstreamFailed> { postOnce(clientOver(engine)) }
            assertTrue(calls.get() > 1, "status $status must be retried before giving up, saw ${calls.get()}")
        }
    }

    @Test
    fun `a successful reactive refresh reports exactly one positive auth outcome`() = runTest {
        val calls = AtomicInteger()
        val refreshes = AtomicInteger()
        val observed = AtomicInteger()
        val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("token")
            override suspend fun refresh(): Credentials {
                refreshes.incrementAndGet()
                return credentials()
            }

            override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
        }
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) {
                respond("unauthorized", HttpStatusCode.Unauthorized, headersOf())
            } else {
                respond("ok", HttpStatusCode.OK, headersOf())
            }
        }
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = auth,
            extraHeaders = { emptyMap() },
            authRefreshObserver = AuthRefreshObserver { observed.incrementAndGet() },
        )

        assertEquals("ok", clientOver(engine).post(context, "{}") { "ok" })
        assertEquals(2, calls.get())
        assertEquals(1, refreshes.get())
        assertEquals(1, observed.get())
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

    // V4-61 REVERSED THIS: it pinned the give-up the operator reported as "it did not retry".
}
