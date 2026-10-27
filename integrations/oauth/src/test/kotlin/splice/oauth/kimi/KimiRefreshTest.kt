// The Kimi token refresh is one exchange per call (failure-handling slice 4): one POST, read as granted, refused for
// good (401, 403, invalid_grant), denied, or unavailable (429, 500, 502, 503, 504), which the turn's retry engine asks
// again 2 s and 4 s later. What the token endpoint hears is what these rows assert. MockEngine, no network.
package splice.oauth.kimi

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

private const val TOKEN_URL = "https://auth.kimi.com/token"

class KimiRefreshTest {

    private val kimiRefresh = KimiRefresh(LogSink {})

    private fun clientOver(engine: MockEngine) = HttpClient(engine)

    private val identityHeaders = mapOf("X-Msh-Device-Id" to "device-123")

    @Test
    fun `a 200 grants the rotated tokens, and the identity headers ride on the POST`() = runTest {
        val seenDeviceHeaders = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seenDeviceHeaders.add(request.headers["X-Msh-Device-Id"])
            respond(
                """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":"3600"}""",
                HttpStatusCode.OK,
                headersOf(),
            )
        }
        val result = kimiRefresh.refresh(TOKEN_URL, "old-refresh", identityHeaders, clientOver(engine))
        val granted = result as RefreshAttempt.Granted
        assertEquals("new-access", granted.tokens.accessToken)
        assertEquals("new-refresh", granted.tokens.refreshToken)
        assertEquals(listOf("device-123"), seenDeviceHeaders)
    }

    @Test
    fun `a busy token endpoint is one exchange that answers unavailable`() = runTest {
        for (status in listOf(429, 500, 502, 503, 504)) {
            val heard = AtomicInteger()
            val engine = MockEngine {
                heard.incrementAndGet()
                respond("busy", HttpStatusCode.fromValue(status), headersOf())
            }
            val result = kimiRefresh.refresh(TOKEN_URL, "refresh", identityHeaders, clientOver(engine))
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
        val thrown = runCatching { kimiRefresh.refresh(TOKEN_URL, "refresh", identityHeaders, clientOver(engine)) }
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
        val result = kimiRefresh.refresh(TOKEN_URL, "dead-refresh", identityHeaders, clientOver(engine))
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
            val result = kimiRefresh.refresh(TOKEN_URL, "dead-refresh", identityHeaders, clientOver(engine))
            assertTrue(result is RefreshAttempt.InvalidGrant, "$status: $result")
            assertEquals(1, heard.get())
        }
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
        KimiRefresh(LogSink { logged.append(it) }).refresh(
            TOKEN_URL,
            "dead-refresh",
            identityHeaders,
            clientOver(engine),
        )
        assertTrue(logged.contains("HTTP 400"), "status must remain diagnosable: $logged")
        assertTrue(!logged.contains(secret), "response body must not reach the log: $logged")
    }
}
