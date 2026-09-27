// V4-354: the request-detail route reads only the one response id from Claude Code's redacted
// transcript. Turning the console view off returns off without opening a file; a missing/pruned
// transcript is named; the route's keys are enumerated so planting an auth header or key fails.
package splice.head.trace.v4354

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.head.trace.TranscriptRequestRoute
import splice.head.trace.TranscriptRoots
import splice.sessions.transcript.MessageConversation
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptMessageSource
import splice.sessions.transcript.TranscriptRole
import java.nio.file.Path

private const val SESSION = "sess-v4354"
private const val RESPONSE = "msg_42_7"

class TranscriptRequestRouteTest {
    private var reads = 0
    private var viewOn = true
    private val source = TranscriptMessageSource { session, _, message ->
        reads += 1
        MessageConversation.Found(
            session,
            message,
            listOf(
                TranscriptMessage(0, TranscriptRole.USER, null, "why is the build red"),
                TranscriptMessage(1, TranscriptRole.ASSISTANT, null, "the config key is invalid", messageId = RESPONSE),
            ),
            earlier = 0,
        )
    }
    private val roots = TranscriptRoots { head -> if (head == "kimi") listOf(Path.of("/synthetic")) else null }
    private val enabled = SessionTranscriptViewEnabled { viewOn }
    private val route = TranscriptRequestRoute(source, roots, enabled, Dispatchers.Unconfined)

    private fun read(on: Boolean, session: String? = SESSION, response: String? = RESPONSE) = runBlocking {
        viewOn = on
        route.read("kimi", session, response)
    }

    @Test
    fun `an on view serves the prompt and selected reply with no auth or key fields`() {
        val reply = read(on = true)
        val body = Json.parseToJsonElement(reply.body).jsonObject

        assertEquals(200, reply.status.value)
        assertEquals(setOf("state", "session_id", "response_message_id", "messages", "earlier"), body.keys)
        assertEquals("found", body.getValue("state").jsonPrimitive.content)
        assertEquals(
            listOf("why is the build red", "the config key is invalid"),
            body.getValue("messages").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content },
        )
        assertEquals(1, reads)
        assertFalse(reply.body.contains("Authorization"), "no request header reaches the route")
    }

    @Test
    fun `turning the view off reads no transcript and says where to turn it back on`() {
        val reply = read(on = false)
        val body = Json.parseToJsonElement(reply.body).jsonObject

        assertEquals(200, reply.status.value)
        assertEquals(setOf("state", "reason"), body.keys)
        assertEquals("off", body.getValue("state").jsonPrimitive.content)
        assertEquals(0, reads, "off is a boundary before any disk read")
        assertFalse(reply.body.contains(SESSION))
        // A second browser omits the first one's query flags but reads the SAME live daemon knob.
        val second = TranscriptRequestRoute(source, roots, enabled, Dispatchers.Unconfined)
        val other = runBlocking { second.read("kimi", SESSION, RESPONSE) }
        assertEquals("off", Json.parseToJsonElement(other.body).jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(0, reads)
    }

    @Test
    fun `a missing or pruned transcript is a named absence`() {
        val missing = TranscriptRequestRoute(
            TranscriptMessageSource { _, _, _ ->
                MessageConversation.Missing("No matching reply in this session's saved transcript.")
            },
            TranscriptRoots { listOf(Path.of("/synthetic")) },
            SessionTranscriptViewEnabled { true },
            Dispatchers.Unconfined,
        ).let { route -> runBlocking { route.read("kimi", SESSION, RESPONSE) } }
        val body = Json.parseToJsonElement(missing.body).jsonObject

        assertEquals("missing", body.getValue("state").jsonPrimitive.content)
        assertEquals(setOf("state", "reason"), body.keys)
        assertFalse(body.getValue("reason").jsonPrimitive.content.isBlank())
    }
}
