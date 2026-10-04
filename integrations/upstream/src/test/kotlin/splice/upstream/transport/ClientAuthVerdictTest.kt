// NEW: V4-220 item 6b — a client (Claude) head reports what upstream said about the login it forwards.
// ClientAuthProvider described itself `present = true` whatever happened, so a Claude head whose
// forwarded login upstream rejected on every turn still read "present" on /api/auth, in doctor and in
// the console. The instrument is a real UpstreamClient over a MockEngine answering one status: only
// the transport sees upstream's answer, so only a post through it can prove the verdict follows it.
// Only a 401 is a rejected login: a 403 is a valid credential refused a resource (a model the plan
// lacks, an org policy), and /login does not fix that.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.auth.ClientAuthProvider
import splice.core.auth.RefreshableAuthProvider

class ClientAuthVerdictTest {

    /** Answers every request with [status]; the body is Anthropic's error shape for a non-2xx. */
    private fun answering(status: HttpStatusCode): UpstreamClient = UpstreamClient(
        totalTimeoutMs = 5_000,
        maxRetries = 1,
        client = HttpClient(
            MockEngine {
                if (status == HttpStatusCode.OK) {
                    respond("fine", status, headersOf())
                } else {
                    respond("""{"type":"error","error":{"type":"authentication_error"}}""", status, headersOf())
                }
            },
        ),
        backoff = { _, _ -> error("a client head's auth answer is never retried") },
    )

    private suspend fun forward(client: UpstreamClient, auth: RefreshableAuthProvider): String =
        client.posted(
            PostContext(url = "https://api.example.test/v1/messages", auth = auth, extraHeaders = { emptyMap() }),
            "{}",
        ) { "ok" }

    /** RED before V4-220: describe() said present whatever upstream answered. */
    @Test
    fun `a client head whose forwarded turn got a 401 reads rejected, not present`() = runTest {
        val auth = ClientAuthProvider("claude-splice")

        val failure = assertThrows<UpstreamFailed> { forward(answering(HttpStatusCode.Unauthorized), auth) }

        assertEquals(401, failure.status)
        assertFalse(auth.describe().present, "upstream rejected the forwarded login")
    }

    @Test
    fun `a 403 is a refused resource, never a rejected login`() = runTest {
        val auth = ClientAuthProvider("claude-splice")

        assertThrows<UpstreamFailed> { forward(answering(HttpStatusCode.Forbidden), auth) }

        assertTrue(auth.describe().present, "a 403 must not send the operator to /login")
    }

    @Test
    fun `a login accepted after a rejection reads present again`() = runTest {
        val auth = ClientAuthProvider("claude-splice")
        assertThrows<UpstreamFailed> { forward(answering(HttpStatusCode.Unauthorized), auth) }

        assertEquals("ok", forward(answering(HttpStatusCode.OK), auth))

        assertTrue(auth.describe().present, "the last answer is the verdict")
    }
}
