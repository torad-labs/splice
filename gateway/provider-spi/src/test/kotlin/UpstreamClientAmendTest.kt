// NEW (RC-4 walls, review 2026-07-24): the one-shot amendBodyOnFailure seam had zero unit
// coverage. Pins: (1) the amended resend is GUARANTEED even when the 400 lands on the last
// permitted attempt (the latch, not the attempt counter, is the budget — an attempt spend here
// made the loop guard eat the resend); (2) the latch is one-shot: a second content-rejecting
// failure falls through to normal classification, never a second amend; (3) the amended bytes
// actually replace the original on the wire. MockEngine — no network.
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.spi.UpstreamClient
import splice.spi.UpstreamFailed

class UpstreamClientAmendTest {

    private val fakeAuth = object : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials? = Credentials.ApiKey("k", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "fake", emptyMap())
    }

    private fun clientOver(engine: MockEngine, maxRetries: Int = 3) = UpstreamClient(
        firstByteTimeoutMs = 5_000,
        totalTimeoutMs = 5_000,
        maxRetries = maxRetries,
        client = HttpClient(engine),
        backoff = { _, _ -> },
    )

    private fun bodyOf(request: HttpRequestData): String =
        (request.body as ByteArrayContent).bytes().toString(Charsets.UTF_8)

    private suspend fun post(
        client: UpstreamClient,
        amend: (Int, String, String) -> String?,
    ): String = client.post(
        url = "https://api.example.test/v1",
        bodyJson = """{"input":"original"}""",
        auth = fakeAuth,
        extraHeaders = { emptyMap() },
        amendBodyOnFailure = amend,
    ) { "ok" }

    @Test
    fun `an amend on the LAST permitted attempt still sends the amended body`() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += bodyOf(request)
            when {
                // two retryable failures spend the attempt budget down to its last slot
                bodies.size <= 2 -> respond("overloaded", HttpStatusCode.ServiceUnavailable, headersOf())
                // the deterministic content 400 lands exactly on attempt == maxRetries - 1
                bodies.size == 3 -> respond(
                    """{"error":{"message":"invalid_encrypted_content"}}""",
                    HttpStatusCode.BadRequest,
                    headersOf(),
                )
                else -> respond("{}", HttpStatusCode.OK, headersOf())
            }
        }
        val result = post(clientOver(engine, maxRetries = 3)) { status, text, _ ->
            // gate like the real provider hook: the 503s also flow through the amender
            if (UpstreamClient.isEncryptedContentError(status, text)) """{"input":"amended"}""" else null
        }
        assertEquals("ok", result)
        assertEquals(4, bodies.size, "the amended resend must go out even at the budget boundary")
        assertEquals("""{"input":"amended"}""", bodies[3], "the resend carries the amended bytes")
        assertEquals(List(3) { """{"input":"original"}""" }, bodies.take(3))
    }

    @Test
    fun `the amend is one-shot - a second content 400 falls through to normal give-up`() = runTest {
        var amends = 0
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += bodyOf(request)
            respond(
                """{"error":{"message":"invalid_encrypted_content"}}""",
                HttpStatusCode.BadRequest,
                headersOf(),
            )
        }
        assertThrows<UpstreamFailed> {
            post(clientOver(engine)) { _, _, _ ->
                amends += 1
                """{"input":"amended-$amends"}"""
            }
        }
        assertEquals(1, amends, "a deterministic 400 amended twice is a loop")
        assertEquals(2, bodies.size, "original, then exactly one amended resend")
        assertEquals("""{"input":"amended-1"}""", bodies[1])
    }

    @Test
    fun `a null amendment leaves the failure to the normal retry plan`() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += bodyOf(request)
            respond("""{"error":{"message":"bad request"}}""", HttpStatusCode.BadRequest, headersOf())
        }
        assertThrows<UpstreamFailed> { post(clientOver(engine)) { _, _, _ -> null } }
        assertTrue(bodies.all { it == """{"input":"original"}""" }, "no body swap without an amendment")
    }
}
