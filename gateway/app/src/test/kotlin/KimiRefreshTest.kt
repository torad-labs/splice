// NEW (G7): regression guard for the KimiRefresh.kt extraction — KimiRefresh().refresh()'s body and
// postRefresh()'s signature changed shape (retry loop now shared, client injected) even though
// observable behavior should not: same 3 attempts, same terminal/retryable statuses, identity
// headers still ride on the POST. MockEngine, no network.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.KimiRefresh
import splice.core.auth.RefreshAttempt
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger

class KimiRefreshTest {

    private fun clientOver(engine: MockEngine) = HttpClient(engine)

    private val identityHeaders = mapOf("X-Msh-Device-Id" to "device-123")

    @Test
    fun `503 then 200 retries once, returns rotated tokens, and identity headers ride on the POST`() = runTest {
        val calls = AtomicInteger()
        val seenDeviceHeaders = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seenDeviceHeaders.add(request.headers["X-Msh-Device-Id"])
            if (calls.incrementAndGet() == 1) {
                respond("service unavailable", HttpStatusCode.ServiceUnavailable, headersOf())
            } else {
                respond(
                    """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":"3600"}""",
                    HttpStatusCode.OK,
                    headersOf(),
                )
            }
        }
        val result = KimiRefresh().refresh(
            "https://auth.kimi.com/token",
            "old-refresh",
            identityHeaders,
            clientOver(engine),
        )
        val granted = result as RefreshAttempt.Granted
        assertEquals("new-access", granted.tokens.accessToken)
        assertEquals("new-refresh", granted.tokens.refreshToken)
        assertEquals(2, calls.get())
        assertEquals(listOf("device-123", "device-123"), seenDeviceHeaders)
    }

    @Test
    fun `401 is terminal without retrying`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("unauthorized", HttpStatusCode.Unauthorized, headersOf())
        }
        val result = KimiRefresh().refresh(
            "https://auth.kimi.com/token",
            "dead-refresh",
            identityHeaders,
            clientOver(engine),
        )
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
        val result = KimiRefresh().refresh(
            "https://auth.kimi.com/token",
            "dead-refresh",
            identityHeaders,
            clientOver(engine),
        )
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
        val result = KimiRefresh().refresh(
            "https://auth.kimi.com/token",
            "refresh",
            identityHeaders,
            clientOver(engine),
        )
        assertTrue(result is RefreshAttempt.Denied)
        assertEquals(3, calls.get())
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
        val stderr = ByteArrayOutputStream()
        val realErr = System.err
        System.setErr(PrintStream(stderr, true))
        try {
            KimiRefresh().refresh(
                "https://auth.kimi.com/token",
                "dead-refresh",
                identityHeaders,
                clientOver(engine),
            )
        } finally {
            System.setErr(realErr)
        }
        val logged = stderr.toString()
        assertTrue(logged.contains("HTTP 400"), "status must remain diagnosable: $logged")
        assertTrue(!logged.contains(secret), "response body must not reach stderr: $logged")
    }
}
