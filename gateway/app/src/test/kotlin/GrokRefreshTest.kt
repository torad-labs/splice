// NEW (G7): grokRefresh end-to-end through the shared retry loop — MockEngine, no network. The
// headline fix: a transient 503 previously returned null on the first attempt; now it retries.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.grokRefresh
import splice.core.auth.RefreshAttempt
import java.util.concurrent.atomic.AtomicInteger

class GrokRefreshTest {

    private fun clientOver(engine: MockEngine) = HttpClient(engine)

    @Test
    fun `503 then 200 retries once and returns the rotated tokens`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) {
                respond("service unavailable", HttpStatusCode.ServiceUnavailable, headersOf())
            } else {
                respond(
                    """{"access_token":"new-access","refresh_token":"new-refresh"}""",
                    HttpStatusCode.OK,
                    headersOf(),
                )
            }
        }
        val result = grokRefresh("https://auth.x.ai/token", "old-refresh", clientOver(engine))
        val granted = result as RefreshAttempt.Granted
        assertEquals("new-access", granted.tokens.accessToken)
        assertEquals("new-refresh", granted.tokens.refreshToken)
        assertEquals(2, calls.get())
    }

    @Test
    fun `401 is terminal without retrying`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("unauthorized", HttpStatusCode.Unauthorized, headersOf())
        }
        val result = grokRefresh("https://auth.x.ai/token", "dead-refresh", clientOver(engine))
        assertTrue(result is RefreshAttempt.InvalidGrant)
        assertEquals(1, calls.get())
    }

    @Test
    fun `invalid_grant body on a 400 is terminal without retrying`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, headersOf())
        }
        val result = grokRefresh("https://auth.x.ai/token", "dead-refresh", clientOver(engine))
        assertTrue(result is RefreshAttempt.InvalidGrant)
        assertEquals(1, calls.get())
    }

    @Test
    fun `all attempts 503 exhausts retries and returns Denied`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("down", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        val result = grokRefresh("https://auth.x.ai/token", "refresh", clientOver(engine))
        assertTrue(result is RefreshAttempt.Denied)
        assertEquals(3, calls.get())
    }

    @Test
    fun `malformed JSON on a 200 response returns Denied without throwing`() = runTest {
        val engine = MockEngine { respond("not json", HttpStatusCode.OK, headersOf()) }
        val result = grokRefresh("https://auth.x.ai/token", "refresh", clientOver(engine))
        assertTrue(result is RefreshAttempt.Denied)
    }
}
