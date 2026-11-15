// Server mode's OpenAI route (docs/specs/server-mode.md, F4): what the provider's server is sent for a chat request
// an OpenAI-style client wrote, and what that client then reads of the server's stream.
package splice.dialect.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatPassthroughTest {
    private val passthrough = ChatPassthrough()

    private fun sent(body: String): PassedChat = passthrough.request(body.toByteArray())

    private fun optionsOf(passed: PassedChat) =
        Json.parseToJsonElement(String(passed.body)).jsonObject.getValue("stream_options").jsonObject

    @Test
    fun `a request that asks for no stream goes on byte for byte`() {
        val written = """{ "model":"m",  "messages":[{"role":"user","content":"hi"}], "temperature": 0.2 }"""
        val passed = sent(written)
        assertArrayEquals(written.toByteArray(), passed.body)
        assertFalse(passed.streamed)
        val explicit = sent("""{"model":"m","stream":false,"messages":[]}""")
        assertFalse(explicit.streamed)
        assertFalse("stream_options" in String(explicit.body), "a server refuses stream_options without a stream")
    }

    @Test
    fun `a streamed request asks the server for usage and keeps every field the client wrote`() {
        val passed = sent("""{"model":"m","stream":true,"messages":[{"role":"user","content":"hi"}],"seed":7}""")
        assertTrue(passed.streamed)
        assertEquals(JsonPrimitive(true), optionsOf(passed)["include_usage"])
        val body = Json.parseToJsonElement(String(passed.body)).jsonObject
        assertEquals(JsonPrimitive(7), body["seed"])
        assertEquals(JsonPrimitive("m"), body["model"])
        assertEquals(setOf("model", "stream", "messages", "seed", "stream_options"), body.keys)
    }

    @Test
    fun `usage is asked of the server even when the client said not to, and its other stream options stay`() {
        val passed = sent("""{"stream":true,"stream_options":{"include_usage":false,"x":1},"messages":[]}""")
        assertEquals(JsonPrimitive(true), optionsOf(passed)["include_usage"])
        assertEquals(JsonPrimitive(1), optionsOf(passed)["x"])
    }

    @Test
    fun `a body that is no JSON goes on as it is, for the server to refuse`() {
        val passed = sent("not json")
        assertEquals("not json", String(passed.body))
        assertFalse(passed.streamed)
    }

    @Test
    fun `the usage frame reaches a client that asked for it and no other`() {
        val asked = sent("""{"stream":true,"stream_options":{"include_usage":true},"messages":[]}""").stream()
        val plain = sent("""{"stream":true,"messages":[]}""").stream()
        assertEquals(USAGE_FRAME, asked.event(USAGE_FRAME))
        assertNull(plain.event(USAGE_FRAME))
        // A frame with a choice is the client's, whatever else it carries.
        for (stream in listOf(asked, plain)) {
            assertEquals(WORD_FRAME, stream.event(WORD_FRAME))
            assertEquals(LAST_WORD_WITH_USAGE, stream.event(LAST_WORD_WITH_USAGE))
            assertEquals(NULL_USAGE_FRAME, stream.event(NULL_USAGE_FRAME))
            assertEquals(": a comment", stream.event(": a comment"))
        }
    }

    @Test
    fun `a stream the server ended without DONE is given it, and one that had it is not given another`() {
        val without = sent("""{"stream":true,"messages":[]}""").stream()
        without.event(WORD_FRAME)
        assertEquals("data: [DONE]", without.ending())
        val with = sent("""{"stream":true,"messages":[]}""").stream()
        with.event(WORD_FRAME)
        assertEquals("data: [DONE]", with.event("data: [DONE]"))
        assertNull(with.ending())
    }
}

private const val WORD_FRAME = """data: {"id":"c1","choices":[{"index":0,"delta":{"content":"hi"}}]}"""
private const val NULL_USAGE_FRAME = """data: {"choices":[{"index":0,"delta":{"content":"!"}}],"usage":null}"""
private const val LAST_WORD_WITH_USAGE =
    """data: {"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3}}"""
private const val USAGE_FRAME = """data: {"id":"c1","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1}}"""
