// The grok token refresh is one exchange per call (failure-handling slice 4): one POST, read as granted, refused for
// good (401, 403, invalid_grant), denied, or unavailable (429, 500, 502, 503, 504), which the turn's retry engine asks
// again 2 s and 4 s later. What the token endpoint hears is what these rows assert. MockEngine, no network.
package splice.oauth.grok

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.RefreshAttempt
import splice.core.util.LogSink
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val TOKEN_URL = "https://auth.x.ai/token"

class GrokRefreshTest {

    private val grokRefresh = GrokRefresh(LogSink {})

    private fun clientOver(engine: MockEngine) = HttpClient(engine)

    @Test
    fun `a 200 grants the rotated tokens`() = runTest {
        val engine = MockEngine {
            respond(
                """{"access_token":"new-access","refresh_token":"new-refresh"}""",
                HttpStatusCode.OK,
                headersOf(),
            )
        }
        val result = grokRefresh.refresh(TOKEN_URL, "old-refresh", clientOver(engine))
        val granted = result as RefreshAttempt.Granted
        assertEquals("new-access", granted.tokens.accessToken)
        assertEquals("new-refresh", granted.tokens.refreshToken)
    }

    @Test
    fun `a busy token endpoint is one exchange that answers unavailable`() = runTest {
        for (status in listOf(429, 500, 502, 503, 504)) {
            val heard = AtomicInteger()
            val engine = MockEngine {
                heard.incrementAndGet()
                respond("busy", HttpStatusCode.fromValue(status), headersOf())
            }
            val result = grokRefresh.refresh(TOKEN_URL, "refresh", clientOver(engine))
            assertTrue(result is RefreshAttempt.Unavailable, "HTTP $status: $result")
            assertEquals(1, heard.get(), "HTTP $status reaches the token endpoint once")
        }
    }

    @Test
    fun `a send that throws reaches the caller after one request`() = runTest {
        val heard = AtomicInteger()
        val engine = MockEngine {
            heard.incrementAndGet()
            throw IOException("connection reset")
        }
        val thrown = runCatching { grokRefresh.refresh(TOKEN_URL, "refresh", clientOver(engine)) }
        assertNotNull(thrown.exceptionOrNull(), "the throw is the caller's to read: $thrown")
        assertEquals(1, heard.get())
    }

    @Test
    fun `401 ends the login's refresh after one request`() = runTest {
        val heard = AtomicInteger()
        val engine = MockEngine {
            heard.incrementAndGet()
            respond("unauthorized", HttpStatusCode.Unauthorized, headersOf())
        }
        val result = grokRefresh.refresh(TOKEN_URL, "dead-refresh", clientOver(engine))
        assertTrue(result is RefreshAttempt.InvalidGrant)
        assertEquals(1, heard.get())
    }

    @Test
    fun `an invalid_grant body ends the login's refresh after one request, even under a busy status`() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.ServiceUnavailable)) {
            val heard = AtomicInteger()
            val engine = MockEngine {
                heard.incrementAndGet()
                respond("""{"error":"invalid_grant"}""", status, headersOf())
            }
            val result = grokRefresh.refresh(TOKEN_URL, "dead-refresh", clientOver(engine))
            assertTrue(result is RefreshAttempt.InvalidGrant, "$status: $result")
            assertEquals(1, heard.get())
        }
    }

    @Test
    fun `malformed JSON on a 200 response returns Denied without throwing`() = runTest {
        val engine = MockEngine { respond("not json", HttpStatusCode.OK, headersOf()) }
        val result = grokRefresh.refresh(TOKEN_URL, "refresh", clientOver(engine))
        assertTrue(result is RefreshAttempt.Denied)
    }

    @Test
    fun `failed refresh logs status without exposing response body`() = runTest {
        val secret = "vendor-secret-response-value"
        val engine = MockEngine {
            respond(
                """{"error":"invalid_grant","detail":"$secret"}""",
                HttpStatusCode.BadRequest,
                headersOf(),
            )
        }
        val logged = StringBuilder()
        GrokRefresh(LogSink { logged.append(it) })
            .refresh(TOKEN_URL, "dead-refresh", clientOver(engine))
        assertTrue(logged.contains("HTTP 400"), "status must remain diagnosable: $logged")
        assertTrue(!logged.contains(secret), "response body must not reach the log: $logged")
    }
}
