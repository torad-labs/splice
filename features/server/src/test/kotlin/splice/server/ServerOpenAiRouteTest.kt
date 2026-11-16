// Server mode's OpenAI route (docs/specs/server-mode.md, F4, openai-route) as an OpenAI-style client meets it, over
// real HTTP on loopback: a script with a person's key asks a served command for its models and for chat completions,
// and each test reads what the client gets back and what the provider's server was asked on its behalf.
package splice.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.ServerLimits
import splice.core.util.LogSink
import java.net.Socket
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ServerOpenAiRouteTest {
    @TempDir lateinit var state: Path

    private val box = ScriptedBox("box")
    private val logged = CopyOnWriteArrayList<String>()
    private var opened: OpenAiRig? = null

    /** Server mode over `claude-team`, whose provider speaks openai-chat on [box] unless [chat] says it does not. */
    private fun serve(limits: ServerLimits = ServerLimits(), chat: Boolean = true): OpenAiRig {
        val upstreams = object : HeadUpstreams {
            override fun chat(head: String): ChatServer? = BoxServer(box.base).takeIf { chat && head == "team" }
        }
        return OpenAiRig(state, upstreams, limits, LogSink { logged += it }).also { opened = it }
    }

    @AfterEach
    fun stop() {
        opened?.close()
        box.stop()
    }

    /** The error of [answer], which must be in OpenAI's shape: one `error` object and nothing beside it. */
    private fun openAiError(answer: HttpResponse<String>): JsonObject = openAiError(answer.body())

    private fun text(error: JsonObject, field: String): String = error.getValue(field).jsonPrimitive.content

    @Test
    fun `without a live key both routes answer 401 in OpenAI's error shape, and the provider is asked nothing`() {
        val rig = serve()
        val unknown = "spk_abcdefgh_${"x".repeat(32)}"
        for (key in listOf(null, unknown)) {
            for (refused in listOf(rig.chat(key, CHAT), rig.models(key))) {
                assertEquals(401, refused.statusCode())
                val error = openAiError(refused)
                assertEquals("invalid_request_error", text(error, "type"))
                assertEquals("invalid_api_key", text(error, "code"))
                assertFalse(unknown in refused.body(), "a refusal never repeats the key")
            }
        }
        assertTrue("no key was sent" in rig.chat(null, CHAT).body())
        assertTrue(box.asked.isEmpty(), "a caller without a key reaches no server")
    }

    @Test
    fun `the models list is the provider server's own, asked with the provider's key and never the person's`() {
        val rig = serve()
        val key = rig.keyFor("ola@example.com")
        val listed = rig.models(key)
        assertEquals(200, listed.statusCode())
        assertEquals(BOX_MODELS, listed.body())
        val asked = box.asked.single()
        assertEquals("/v1/models", asked.path)
        assertEquals("Bearer $BOX_KEY", asked.headers["authorization"])
        assertEquals("blue", asked.headers["x-box-team"])
        assertFalse(asked.headers.values.any { key in it }, "the person's key stays on this machine")
    }

    @Test
    fun `a models list asked the way Claude Code asks it still comes from the head`() {
        val rig = serve()
        val listed = rig.models(rig.keyFor("ola@example.com"), "anthropic-version" to "2023-06-01")
        assertEquals(HEAD_MODELS, listed.body())
        assertTrue(box.asked.isEmpty())
    }

    @Test
    fun `a command whose provider speaks another dialect is answered by its head, as before`() {
        val rig = serve(chat = false)
        val key = rig.keyFor("ola@example.com")
        assertEquals(HEAD_MODELS, rig.models(key).body())
        val completion = rig.chat(key, CHAT)
        assertEquals(404, completion.statusCode())
        assertEquals(HEAD_NO_ROUTE, completion.body())
        assertTrue(box.asked.isEmpty())
    }

    @Test
    fun `a completion that asks for no stream reaches the server as it was sent and is answered as the server did`() {
        val rig = serve()
        val key = rig.keyFor("ola@example.com")
        val sent = """{ "model":"team-model",  "messages":[{"role":"user","content":"olá"}], "temperature": 0.2 }"""
        val answered = rig.chat(key, sent, query = "?api-version=2")
        assertEquals(200, answered.statusCode())
        val answer = Json.parseToJsonElement(answered.body()).jsonObject
        val said = answer.getValue("choices").jsonArray.single().jsonObject.getValue("message").jsonObject
        assertEquals("from box", text(said, "content"))
        assertEquals(7, answer.getValue("usage").jsonObject.getValue("prompt_tokens").jsonPrimitive.content.toInt())
        val asked = box.asked.single()
        assertEquals("/v1/chat/completions?api-version=2", asked.path)
        assertEquals(sent, asked.body)
        assertEquals("Bearer $BOX_KEY", asked.headers["authorization"])
        assertFalse(asked.headers.values.any { key in it }, "the person's key stays on this machine")
    }

    @Test
    fun `what the server refuses reaches the client in the server's own status and words`() {
        val rig = serve()
        box.refuses = 422
        val refused = rig.chat(rig.keyFor("ola@example.com"), CHAT)
        assertEquals(422, refused.statusCode())
        assertEquals("""{"error":{"message":"box says 422"}}""", refused.body())
    }

    @Test
    fun `a streamed completion reaches the client event by event as the server writes it, and ends with DONE`() {
        val rig = serve()
        // The server writes a word every 300 ms and ends its stream without `[DONE]`.
        box.events = listOf("one", "two", "three").map { """data: {"choices":[{"delta":{"content":"$it"}}]}""" }
        box.gapMs = 300
        val heard = rig.stream(rig.keyFor("ola@example.com"), STREAMED_CHAT)
        assertEquals(box.events + DONE_EVENT, heard.map { it.second })
        val (firstAt, lastAt) = heard.first().first to heard.last().first
        assertTrue(lastAt - firstAt >= 500, "the first word came ${lastAt - firstAt} ms before the end")
    }

    @Test
    fun `an event the stream's reader leaves out never reaches the client, and DONE is not said twice`() {
        val rig = serve()
        val heard = rig.stream(rig.keyFor("ola@example.com"), STREAMED_CHAT).map { it.second }
        assertEquals(listOf(WORD_EVENT, DONE_EVENT), heard)
        assertEquals(STREAMED_CHAT, box.asked.single().body)
    }

    @Test
    fun `a stream the server broke off ends, after the events it sent, with one error event and no DONE`() {
        val rig = serve()
        box.events = listOf("one", "two", "three").map { """data: {"choices":[{"delta":{"content":"$it"}}]}""" }
        box.cutAfter = 2
        val heard = rig.stream(rig.keyFor("ola@example.com"), STREAMED_CHAT).map { it.second }
        assertEquals(box.events.take(2), heard.take(2), "what the server sent before it broke off")
        assertEquals(3, heard.size, "one event after the server's two, and no [DONE]: $heard")
        val error = openAiError(heard.last().removePrefix("data: "))
        assertEquals("server_error", text(error, "type"))
        assertTrue("the model server behind claude-team broke off its answer" in text(error, "message"))
        assertTrue(logged.any { "broke off its answer" in it }, "the log says the answer broke off: $logged")
    }

    @Test
    fun `an answer that is no stream and breaks off partway is a 502 in OpenAI's shape, never half a body`() {
        val rig = serve()
        box.cutAfter = 0
        val answered = rig.chat(rig.keyFor("ola@example.com"), CHAT)
        assertEquals(502, answered.statusCode())
        assertEquals("application/json", answered.headers().firstValue("content-type").get().substringBefore(';'))
        val error = openAiError(answered)
        assertEquals("server_error", text(error, "type"))
        assertTrue("the model server behind claude-team broke off its answer" in text(error, "message"))
        assertTrue(logged.any { "broke off its answer" in it }, "the log says the answer broke off: $logged")
    }

    @Test
    fun `a person at their cap is refused 429 with Retry-After, in OpenAI's shape here and Anthropic's for a turn`() {
        val rig = serve(ServerLimits(inflightPerPerson = 1))
        val key = rig.keyFor("ola@example.com")
        val gate = CountDownLatch(1).also { box.gate = it }
        val held = rig.chatLater(key, STREAMED_CHAT)
        rig.await("the first completion reached the server") { box.asked.size == 1 }

        val second = rig.chat(key, CHAT)
        assertEquals(429, second.statusCode())
        assertTrue(second.headers().firstValue("retry-after").get().toInt() >= 1)
        val error = openAiError(second)
        assertEquals("rate_limit_error", text(error, "type"))
        assertEquals("rate_limit_exceeded", text(error, "code"))
        assertEquals(JsonNull, error["param"])

        // The cap is the person's, whichever route a turn comes in on, and each route refuses in its client's shape.
        val turn = rig.turn(key)
        assertEquals(429, turn.statusCode())
        assertEquals("error", text(Json.parseToJsonElement(turn.body()).jsonObject, "type"))
        // The models list is no turn, and another person holds none of this one's slots.
        assertEquals(200, rig.models(key).statusCode())
        assertEquals(200, rig.chat(rig.keyFor("ana@example.com"), CHAT).statusCode())

        gate.countDown()
        assertEquals(200, held.get(10, TimeUnit.SECONDS).statusCode())
        rig.await("the slot came back when the completion ended") { rig.chat(key, CHAT).statusCode() == 200 }
    }

    @Test
    fun `a client that hangs up on a completion gives its slot back`() {
        val rig = serve(ServerLimits(inflightPerPerson = 1))
        val key = rig.keyFor("ola@example.com")
        box.gate = CountDownLatch(1)
        Socket("127.0.0.1", rig.port).use { client ->
            val head = "POST /claude-team/v1/chat/completions HTTP/1.1\r\nHost: gpu-box\r\n" +
                "Authorization: Bearer $key\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${STREAMED_CHAT.toByteArray().size}\r\n\r\n"
            client.getOutputStream().write((head + STREAMED_CHAT).toByteArray())
            client.getOutputStream().flush()
            rig.await("the completion reached the server") { box.asked.size == 1 }
            assertEquals(429, rig.chat(key, CHAT).statusCode())
        }
        rig.await("the slot came back when the client left") { rig.chat(key, CHAT).statusCode() == 200 }
    }
}
