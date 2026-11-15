// What server mode's OpenAI route hands a provider's server of what a client sent (docs/specs/server-mode.md, F4,
// openai-route), over real HTTP on loopback. A person's key is the server's secret: it admits the request and goes no
// further, in whatever header or query parameter the client put it. Of the client's other headers only the ones the
// provider's `forward_headers` names ride on, as on a head's own turn; each test reads what the provider's server got.
package splice.server

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

internal class ServerOpenAiHeadersTest {
    @TempDir lateinit var state: Path

    private val box = ScriptedBox("box")
    private val logged = CopyOnWriteArrayList<String>()
    private var opened: OpenAiRig? = null

    /** Server mode over `claude-team`, whose provider speaks openai-chat on [box] and names [forwarded] in its
     *  `forward_headers`. */
    private fun serve(vararg forwarded: String): OpenAiRig {
        val provider = BoxServer(box.base, forwarded.toList())
        val upstreams = object : HeadUpstreams {
            override fun chat(head: String): ChatServer? = provider.takeIf { head == "team" }
        }
        return OpenAiRig(state, upstreams, log = LogSink { logged += it }).also { opened = it }
    }

    @AfterEach
    fun stop() {
        opened?.close()
        box.stop()
    }

    /** The headers of the one request [box] got since [from], which must carry the provider's key and not [key]. */
    private fun handedOn(from: Int, key: String): Map<String, String> {
        val headers = box.asked.drop(from).single().headers
        assertEquals("Bearer $BOX_KEY", headers["authorization"])
        val carriers = headers.filterValues { key in it }.keys
        assertTrue(carriers.isEmpty(), "the provider's server was handed the person's key in $carriers")
        return headers
    }

    @Test
    fun `a key sent in a second header admits the request and reaches no provider, on either route`() {
        val rig = serve()
        val key = rig.keyFor("ola@example.com")
        // Continue's OpenAI provider sends the key as `api-key` beside Authorization; other tools name it themselves.
        val beside = listOf("api-key" to key, "x-team-key" to key, "x-custom" to "Bearer $key", "x-goog-api-key" to key)

        assertEquals(200, rig.chat(key, CHAT, extra = beside).statusCode())
        handedOn(from = 0, key)
        assertEquals(listOf(WORD_EVENT, DONE_EVENT), rig.stream(key, STREAMED_CHAT, beside).map { it.second })
        handedOn(from = 1, key)
        assertEquals(BOX_MODELS, rig.models(key, *beside.toTypedArray()).body())
        handedOn(from = 2, key)

        // A key sent as x-api-key, with none as a bearer, is the same key and stays here the same way.
        val asKey = listOf("x-api-key" to key, "x-team-key" to key)
        assertEquals(200, rig.chat(key = null, CHAT, extra = asKey).statusCode())
        handedOn(from = 3, key)
    }

    @Test
    fun `a key in the query, in the body's type, or behind a run that looks like a key reaches no provider`() {
        val rig = serve("x-team-tag")
        val key = rig.keyFor("ola@example.com")
        // A run with a key's shape that ends in the first letters of the real key, which follows it.
        val decoy = "spk_aaaaaaaa_${"x".repeat(37)}$key"
        val sent = listOf("Content-Type" to "application/json; k=$key", "x-team-tag" to decoy)
        val escaped = key.replace("_", "%5F")
        val query = "?api-key=$key&api-version=2&k=$escaped"
        assertEquals(200, rig.chat(key, CHAT, query, sent).statusCode())
        val asked = box.asked.single()
        assertEquals("/v1/chat/completions?api-version=2", asked.path)
        assertEquals(CHAT, asked.body)
        val headers = handedOn(from = 0, key)
        assertNull(headers["x-team-tag"])
        assertFalse(logged.any { key in it || escaped in it }, "the log never repeats a key")

        // With the key in its only parameter, nothing of the query is left to send.
        assertEquals(200, rig.chat(key, CHAT, "?key=$key").statusCode())
        assertEquals("/v1/chat/completions", box.asked.last().path)
    }

    @Test
    fun `of a client's own headers only the ones forward_headers names reach the provider's server`() {
        val rig = serve("x-team-tag")
        val key = rig.keyFor("ola@example.com")
        val own = listOf(
            "x-team-tag" to "research",
            "Cookie" to "session=abc",
            "X-Forwarded-For" to "203.0.113.7",
            "Forwarded" to "for=203.0.113.7",
            "X-Real-IP" to "203.0.113.7",
            "User-Agent" to "team-script/1.0",
            "OpenAI-Organization" to "org-team",
            "x-stainless-lang" to "python",
        )
        assertEquals(200, rig.chat(key, CHAT, extra = own).statusCode())
        val headers = handedOn(from = 0, key)
        assertEquals("research", headers["x-team-tag"])
        assertEquals("application/json", headers["content-type"], "the body's type rides with the body")
        assertEquals("blue", headers["x-box-team"], "the provider's own headers ride as they are configured")
        for ((name, value) in own.drop(1)) {
            assertNotEquals(value, headers[name.lowercase()], "$name stays on this machine")
        }
        assertTrue(logged.none { "did not forward" in it }, "nothing named was held back: $logged")
    }

    @Test
    fun `a named header the client sends replaces the provider's own value of that name, and only then`() {
        val rig = serve("x-box-team")
        val key = rig.keyFor("ola@example.com")
        rig.chat(key, CHAT, extra = listOf("X-Box-Team" to "red"))
        assertEquals("red", handedOn(from = 0, key)["x-box-team"])
        rig.chat(key, CHAT)
        assertEquals("blue", handedOn(from = 1, key)["x-box-team"])
    }

    @Test
    fun `a named header that holds a person's key stays here, the request goes on, and the log names the header`() {
        val rig = serve("x-team-tag", "x-trace")
        val key = rig.keyFor("ola@example.com")
        val other = rig.keyFor("ana@example.com")
        // Another person's key is the server's secret as much as the caller's own.
        val sent = listOf("x-team-tag" to "team=research; key=$other", "x-trace" to "t-1")
        assertEquals(200, rig.chat(key, CHAT, extra = sent).statusCode())
        val headers = handedOn(from = 0, key)
        assertNull(headers["x-team-tag"])
        assertEquals("t-1", headers["x-trace"])
        assertFalse(headers.values.any { other in it })
        val line = logged.single { "did not forward" in it }
        assertTrue("x-team-tag" in line && "x-trace" !in line, line)
        assertFalse(logged.any { key in it || other in it }, "the log never repeats a key")
    }
}
