// NEW (G7): regression guard for the KimiRefresh.kt extraction — kimiRefresh()'s body and
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.app.kimiRefresh
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
        val result = kimiRefresh(
            "https://auth.kimi.com/token",
            "old-refresh",
            identityHeaders,
            clientOver(engine),
        )
        assertEquals("new-access", result?.accessToken)
        assertEquals("new-refresh", result?.refreshToken)
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
        assertNull(kimiRefresh("https://auth.kimi.com/token", "dead-refresh", identityHeaders, clientOver(engine)))
        assertEquals(1, calls.get())
    }

    @Test
    fun `invalid_grant body on a 400 is terminal without retrying`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, headersOf())
        }
        assertNull(kimiRefresh("https://auth.kimi.com/token", "dead-refresh", identityHeaders, clientOver(engine)))
        assertEquals(1, calls.get())
    }

    @Test
    fun `all attempts 503 exhausts retries and returns null`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("down", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        assertNull(kimiRefresh("https://auth.kimi.com/token", "refresh", identityHeaders, clientOver(engine)))
        assertEquals(3, calls.get())
    }
}
