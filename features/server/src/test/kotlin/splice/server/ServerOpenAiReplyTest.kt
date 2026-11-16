// What an OpenAI-style client gets back of a provider's answer (docs/specs/server-mode.md, F4), over real HTTP on
// loopback. A provider's server sends headers of its own with every answer: a cookie, a request id, its rate-limit
// counts. They are that server's business with this machine. The client gets the answer's status, its body, its
// content type and, when the server refused, its Retry-After, and nothing else the server sent.
package splice.server

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.http.HttpResponse
import java.nio.file.Path

internal class ServerOpenAiReplyTest {
    @TempDir lateinit var state: Path

    private val box = ScriptedBox("box").apply { replies = OWN }
    private var opened: OpenAiRig? = null

    /** Server mode over `claude-team`, whose provider speaks openai-chat on [box], and one person's key. */
    private fun serve(): Pair<OpenAiRig, String> {
        val upstreams = object : HeadUpstreams {
            override fun chat(head: String): ChatServer? = BoxServer(box.base).takeIf { head == "team" }
        }
        val rig = OpenAiRig(state, upstreams).also { opened = it }
        return rig to rig.keyFor("ola@example.com")
    }

    @AfterEach
    fun stop() {
        opened?.close()
        box.stop()
    }

    private fun typeOf(answer: HttpResponse<String>): String =
        answer.headers().firstValue("content-type").orElse("").substringBefore(';')

    /** [answer] carries nothing of the provider server's own: none of its headers by name, and its cookie nowhere. */
    private fun assertNoneOfItsOwn(answer: HttpResponse<String>, what: String) {
        val headers = answer.headers().map().mapKeys { it.key.lowercase() }
        assertEquals(emptySet<String>(), headers.keys intersect OWN.keys.map(String::lowercase).toSet(), what)
        assertFalse(headers.values.flatten().any { COOKIE in it }, "$what: the server's cookie came back")
    }

    @Test
    fun `a completion, streamed or not, and the models list come back without the server's own headers`() {
        val (rig, key) = serve()
        val whole = rig.chat(key, CHAT)
        assertEquals(200, whole.statusCode())
        assertEquals("application/json", typeOf(whole))
        assertNoneOfItsOwn(whole, "a completion")

        val streamed = rig.chat(key, STREAMED_CHAT)
        assertEquals(200, streamed.statusCode())
        assertEquals("text/event-stream", typeOf(streamed))
        assertNoneOfItsOwn(streamed, "a streamed completion")

        val listed = rig.models(key)
        assertEquals(BOX_MODELS, listed.body())
        assertEquals("application/json", typeOf(listed))
        assertNoneOfItsOwn(listed, "the models list")
    }

    @Test
    fun `a refusal keeps the server's status, its words and its Retry-After, and none of its other headers`() {
        val (rig, key) = serve()
        box.refuses = 429
        box.replies = OWN + ("Retry-After" to "7")
        val refused = rig.chat(key, CHAT)
        assertEquals(429, refused.statusCode())
        assertEquals("""{"error":{"message":"box says 429"}}""", refused.body())
        assertEquals("7", refused.headers().firstValue("retry-after").orElse(null))
        assertNoneOfItsOwn(refused, "a refusal")
    }

    @Test
    fun `Retry-After beside an answer that is no refusal stays at the server with the rest`() {
        val (rig, key) = serve()
        box.replies = OWN + ("Retry-After" to "7")
        val answered = rig.chat(key, CHAT)
        assertEquals(200, answered.statusCode())
        assertFalse(answered.headers().firstValue("retry-after").isPresent, "an answer says no time to ask again")
        assertNoneOfItsOwn(answered, "a completion")
    }

    @Test
    fun `a turn on the Messages route still comes back with its head's own headers`() {
        val (rig, key) = serve()
        val turn = rig.turn(key)
        assertEquals(200, turn.statusCode())
        assertEquals(HEAD_REQUEST_ID, turn.headers().firstValue("request-id").orElse(null))
    }
}

private const val COOKIE = "s3cr3t-cookie"

// What the provider's server sends beside every answer, for itself.
private val OWN = mapOf(
    "Set-Cookie" to "box_session=$COOKIE; Path=/; HttpOnly",
    "X-Request-Id" to "req-box-77",
    "X-RateLimit-Remaining-Requests" to "41",
    "Openai-Processing-Ms" to "312",
)
