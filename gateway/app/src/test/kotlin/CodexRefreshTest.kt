// NEW (G7): codexRefresh end-to-end through the shared retry loop — MockEngine, no network. The
// headline fix: a transient 503 previously returned null on the first attempt; now it retries.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.app.codexRefresh
import java.util.concurrent.atomic.AtomicInteger

class CodexRefreshTest {

    private fun clientOver(engine: MockEngine) = HttpClient(engine)

    @Test
    fun `503 then 200 retries once and returns the rotated tokens`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) {
                respond("service unavailable", HttpStatusCode.ServiceUnavailable, headersOf())
            } else {
                respond(
                    """{"access_token":"new-access","refresh_token":"new-refresh","id_token":"new-id"}""",
                    HttpStatusCode.OK,
                    headersOf(),
                )
            }
        }
        val result = codexRefresh("https://auth.openai.com/token", "old-refresh", clientOver(engine))
        assertEquals("new-access", result?.accessToken)
        assertEquals("new-refresh", result?.refreshToken)
        assertEquals("new-id", result?.idToken)
        assertEquals(2, calls.get())
    }

    @Test
    fun `401 is terminal without retrying`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("unauthorized", HttpStatusCode.Unauthorized, headersOf())
        }
        assertNull(codexRefresh("https://auth.openai.com/token", "dead-refresh", clientOver(engine)))
        assertEquals(1, calls.get())
    }

    @Test
    fun `invalid_grant body on a 400 is terminal without retrying`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, headersOf())
        }
        assertNull(codexRefresh("https://auth.openai.com/token", "dead-refresh", clientOver(engine)))
        assertEquals(1, calls.get())
    }

    @Test
    fun `all attempts 503 exhausts retries and returns null`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("down", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        assertNull(codexRefresh("https://auth.openai.com/token", "refresh", clientOver(engine)))
        assertEquals(3, calls.get())
    }

    @Test
    fun `malformed JSON on a 200 response returns null without throwing`() = runTest {
        val engine = MockEngine { respond("not json", HttpStatusCode.OK, headersOf()) }
        assertNull(codexRefresh("https://auth.openai.com/token", "refresh", clientOver(engine)))
    }
}
