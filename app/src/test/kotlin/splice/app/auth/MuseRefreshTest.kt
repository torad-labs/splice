// NEW: Muse exchanges an account token for a subscription key, not rotated OAuth tokens.
// MockEngine pins both wire modes and hard failures without contacting Meta or opening a browser.
package splice.app.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.GATEWAY_VERSION
import splice.core.util.WallClock
import splice.provider.muse.MuseMintAttempt
import splice.provider.muse.MuseMintMode
import java.time.Instant

class MuseRefreshTest {
    private val endpoint = "https://api.meta.ai/muse-code/key"
    private val active = """{"api_key":"inference-key","is_subs_active":true,"require_payment":false}"""

    @Test
    fun `onboarding sends account bearer and splice user agent with JSON onboard true`() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls += 1
            assertEquals(endpoint, request.url.toString())
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer account-token", request.headers["Authorization"])
            assertNull(request.headers["x-api-version"])
            assertEquals("splice/$GATEWAY_VERSION", request.headers["User-Agent"])
            assertNull(request.headers["x-client-id"])
            assertNull(request.headers["x-tbh-session-id"])
            assertNull(request.headers["x-meta-ai-gateway-session-id"])
            assertEquals("application/json", request.headers["Accept"])
            val body = request.body as TextContent
            assertEquals("application/json", body.contentType.toString())
            assertEquals(Json.parseToJsonElement("""{"onboard":true}"""), Json.parseToJsonElement(body.text))
            respond(active, HttpStatusCode.OK)
        }
        HttpClient(engine).use { client ->
            val result = MuseRefresh().refresh(endpoint, "account-token", MuseMintMode.ONBOARD, client)
            assertTrue(result is MuseMintAttempt.Granted)
            assertEquals("inference-key", (result as MuseMintAttempt.Granted).key.apiKey)
        }
        assertEquals(1, calls)
    }

    @Test
    fun `refresh sends an empty JSON object without refresh grant or inference key`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer account-token", request.headers["Authorization"])
            assertNull(request.headers["x-api-version"])
            assertEquals("splice/$GATEWAY_VERSION", request.headers["User-Agent"])
            assertNull(request.headers["x-client-id"])
            assertNull(request.headers["x-tbh-session-id"])
            assertNull(request.headers["x-meta-ai-gateway-session-id"])
            assertEquals("{}", (request.body as TextContent).text)
            respond(active, HttpStatusCode.OK)
        }
        HttpClient(engine).use { client ->
            val result = MuseRefresh().refresh(endpoint, "account-token", MuseMintMode.REFRESH, client)
            assertTrue(result is MuseMintAttempt.Granted)
        }
    }

    @Test
    fun `429 is hard with exactly one call even with retry after`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls += 1
            respond("private-response", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1"))
        }
        HttpClient(engine).use { client ->
            val result = MuseRefresh().refresh(endpoint, "account-token", MuseMintMode.REFRESH, client)
            assertTrue(result is MuseMintAttempt.RateLimited)
            assertEquals(1_000L, (result as MuseMintAttempt.RateLimited).retryAfterMs)
        }
        assertEquals(1, calls)
    }

    @Test
    fun `mint rate limits preserve bounded seconds or HTTP-date advice and reject garbage`() = runTest {
        val clock = WallClock { Instant.parse("2026-09-15T00:00:00Z").toEpochMilli() }
        val cases = mapOf(
            "15" to 15_000L,
            "Tue, 15 Sep 2026 00:01:00 GMT" to 60_000L,
            "Mon, 14 Sep 2026 23:59:00 GMT" to 0L,
            "999999999999999999999999999999" to 3_600_000L,
            "-1" to null,
            "garbage" to null,
        )
        for ((header, expected) in cases) {
            var calls = 0
            HttpClient(
                MockEngine {
                    calls += 1
                    respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", header))
                },
            ).use { client ->
                val result = MuseRefresh(clock).refresh(endpoint, "account-token", MuseMintMode.REFRESH, client)
                assertTrue(result is MuseMintAttempt.RateLimited)
                assertEquals(expected, (result as MuseMintAttempt.RateLimited).retryAfterMs, header)
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `401 and auth-body 403 reject the account token without retrying or echoing response`() = runTest {
        var unauthorizedCalls = 0
        val unauthorizedEngine = MockEngine {
            unauthorizedCalls += 1
            respond("private-response", HttpStatusCode.Unauthorized)
        }
        HttpClient(unauthorizedEngine).use { client ->
            val result = MuseRefresh().refresh(endpoint, "account-token", MuseMintMode.REFRESH, client)
            assertTrue(result is MuseMintAttempt.InvalidAccountToken)
            assertFalse(result.toString().contains("private-response"))
        }
        assertEquals(1, unauthorizedCalls)

        var forbiddenCalls = 0
        val forbiddenEngine = MockEngine {
            forbiddenCalls += 1
            respond("unauthenticated:bad-credentials", HttpStatusCode.Forbidden)
        }
        HttpClient(forbiddenEngine).use { client ->
            val result = MuseRefresh().refresh(endpoint, "account-token", MuseMintMode.REFRESH, client)
            assertTrue(result is MuseMintAttempt.InvalidAccountToken)
        }
        assertEquals(1, forbiddenCalls)
    }

    @Test
    fun `a plan 403 on the mint is denied without latching the account token`() = runTest {
        val engine = MockEngine { respond("plan limit exceeded", HttpStatusCode.Forbidden) }
        HttpClient(engine).use { client ->
            val result = MuseRefresh().refresh(endpoint, "account-token", MuseMintMode.REFRESH, client)
            assertTrue(result is MuseMintAttempt.Denied)
        }
    }

    @Test
    fun `a second mint through one MuseRefresh client still succeeds`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls += 1
            respond(active, HttpStatusCode.OK)
        }
        HttpClient(engine).use { client ->
            val refresh = MuseRefresh()
            val first = refresh.refresh(
                endpoint,
                "account-token",
                MuseMintMode.REFRESH,
                client,
            )
            val second = refresh.refresh(
                endpoint,
                "account-token",
                MuseMintMode.REFRESH,
                client,
            )
            assertTrue(first is MuseMintAttempt.Granted)
            assertTrue(second is MuseMintAttempt.Granted)
        }
        assertEquals(2, calls)
    }

    @Test
    fun `other failed statuses are denied in one call with status-only detail`() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.ServiceUnavailable)) {
            var calls = 0
            val engine = MockEngine {
                calls += 1
                respond("private-response", status)
            }
            HttpClient(engine).use { client ->
                val result = MuseRefresh().refresh(endpoint, "account-token", MuseMintMode.REFRESH, client)
                assertTrue(result is MuseMintAttempt.Denied)
                val detail = (result as MuseMintAttempt.Denied).detail
                assertTrue(detail.contains(status.value.toString()))
                assertFalse(detail.contains("private-response"))
                assertFalse(detail.contains("account-token"))
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `a redirect is denied without forwarding account bearer to its destination`() = runTest {
        val urls = mutableListOf<String>()
        val engine = MockEngine { request ->
            urls += request.url.toString()
            respond("", HttpStatusCode.TemporaryRedirect, headersOf("Location", "https://other.invalid/stolen"))
        }
        HttpClient(engine).use { client ->
            val result = MuseRefresh().refresh(endpoint, "account-token", MuseMintMode.REFRESH, client)
            assertTrue(result is MuseMintAttempt.Denied)
        }
        assertEquals(listOf(endpoint), urls)
    }

    @Test
    fun `inactive subscription and payment required retain a safe action without a key`() = runTest {
        val bodies = listOf(
            """{"is_subs_active":false,"require_payment":false,"action_url":"https://www.meta.ai/"}""",
            """{"is_subs_active":true,"require_payment":true,"action_url":"https://www.meta.ai/"}""",
        )
        for (body in bodies) {
            HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }).use { client ->
                val result = MuseRefresh().refresh(endpoint, "account-token", MuseMintMode.REFRESH, client)
                assertTrue(result is MuseMintAttempt.SubscriptionRequired)
                assertEquals("https://www.meta.ai/", (result as MuseMintAttempt.SubscriptionRequired).actionUrl)
            }
        }
    }

    @Test
    fun `malformed flags and missing or non-string keys cannot grant inference credentials`() = runTest {
        val bodies = listOf(
            "{}",
            "null",
            "invalid-json",
            """{"api_key":"secret","is_subs_active":"true","require_payment":false}""",
            """{"api_key":"secret","is_subs_active":true,"require_payment":null}""",
            """{"is_subs_active":true,"require_payment":false}""",
            """{"api_key":123,"is_subs_active":true,"require_payment":false}""",
        )
        for (body in bodies) {
            HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }).use { client ->
                val result = MuseRefresh().refresh(endpoint, "account-token", MuseMintMode.REFRESH, client)
                assertTrue(result is MuseMintAttempt.Denied, "malformed response must fail closed")
                assertFalse((result as MuseMintAttempt.Denied).detail.contains("secret"))
            }
        }
    }

    @Test
    fun `cancellation is not classified as an auth rejection`() {
        HttpClient(MockEngine { throw CancellationException("cancelled") }).use { client ->
            assertThrows(CancellationException::class.java) {
                runTest { MuseRefresh().refresh(endpoint, "account-token", MuseMintMode.REFRESH, client) }
            }
        }
    }
}
