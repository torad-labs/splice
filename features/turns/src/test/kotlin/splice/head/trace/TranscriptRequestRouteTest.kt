// the request-detail route reads only the one response id from Claude Code's redacted
// transcript. Turning the console view off returns off without opening a file; a missing/pruned
// transcript is named; the route's keys are enumerated so planting an auth header or key fails.
package splice.head.trace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.LogSink
import splice.sessions.transcript.MessageConversation
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptMessageSource
import splice.sessions.transcript.TranscriptRole
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    fun `conversation projection preserves parallel tool-use identity`() = runBlocking {
        val calls = listOf(
            TranscriptMessage(0, TranscriptRole.ASSISTANT, null, "{}", "Read", false, RESPONSE, "first"),
            TranscriptMessage(1, TranscriptRole.ASSISTANT, null, "{}", "Read", false, RESPONSE, "second"),
            TranscriptMessage(2, TranscriptRole.TOOL, null, "first body", result = true, toolUseId = "first"),
            TranscriptMessage(3, TranscriptRole.TOOL, null, "second body", result = true, toolUseId = "second"),
        )
        val paired = TranscriptRequestRoute(
            TranscriptMessageSource { session, _, response -> MessageConversation.Found(session, response, calls, 9) },
            roots,
            enabled,
            Dispatchers.Unconfined,
        )
        val body = Json.parseToJsonElement(paired.read("kimi", SESSION, RESPONSE).body).jsonObject
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("first", "second", "first", "second"),
            messages.map { it.getValue("tool_use_id").jsonPrimitive.content },
        )
        assertEquals("9", body.getValue("earlier").jsonPrimitive.content)
        assertEquals(listOf("true", "true", null, null), messages.map { it["selected"]?.jsonPrimitive?.content })
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a blocked source is interrupted at the deadline and returns a reason`() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val slow = TranscriptRequestRoute(
            TranscriptMessageSource { _, _, _ ->
                entered.countDown()
                try {
                    release.await()
                    MessageConversation.Missing("the slow source finished without finding a reply")
                } catch (failure: InterruptedException) {
                    interrupted.set(true)
                    throw failure
                }
            },
            roots,
            enabled,
            Dispatchers.Default,
            readTimeoutMs = 50L,
        )
        val read = async { slow.read("kimi", SESSION, RESPONSE) }
        try {
            runCurrent()
            assertTrue(entered.await(1, TimeUnit.SECONDS), "the source must start before advancing its deadline")
            advanceTimeBy(50)
            runCurrent()
            if (!read.isCompleted) release.countDown()
            val reply = read.await()
            val body = Json.parseToJsonElement(reply.body).jsonObject
            assertEquals("unavailable", body.getValue("state").jsonPrimitive.content)
            assertTrue(interrupted.get(), "the timed-out disk worker must not continue scanning")
            assertEquals(200, reply.status.value)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a lookup that cannot finish is unavailable rather than claiming the reply is missing`() = runBlocking {
        val unavailable = TranscriptRequestRoute(
            TranscriptMessageSource { _, _, _ ->
                MessageConversation.Unavailable("Reading this conversation took too long. Open the session.")
            },
            roots,
            enabled,
            Dispatchers.Unconfined,
        )
        val reply = unavailable.read("kimi", SESSION, RESPONSE)
        val body = Json.parseToJsonElement(reply.body).jsonObject
        assertEquals(200, reply.status.value)
        assertEquals("unavailable", body.getValue("state").jsonPrimitive.content)
        assertEquals(setOf("state", "reason"), body.keys)
        assertEquals(
            "Reading this conversation took too long. Open the session.",
            body.getValue("reason").jsonPrimitive.content,
        )
    }

    @Test
    fun `source failures log the safe diagnosis and source site once per failed read`() = runBlocking {
        val logged = mutableListOf<String>()
        val failed = TranscriptRequestRoute(
            TranscriptMessageSource { _, _, _ -> throw IOException("/synthetic/$SESSION/$RESPONSE private text") },
            roots,
            enabled,
            Dispatchers.Unconfined,
            log = LogSink { logged += it },
        )
        val expected = Regex(
            "\\[transcript] saved transcript read failed: failure " +
                "\\(message withheld: it may quote file bytes\\) at TranscriptRequestRouteTest\\.kt:\\d+",
        )
        repeat(2) { index ->
            val reply = failed.read("kimi", SESSION, RESPONSE)
            assertEquals(500, reply.status.value)
            assertFalse(reply.body.contains(SESSION))
            assertFalse(reply.body.contains(RESPONSE))
            assertEquals(index + 1, logged.size)
            assertTrue(logged.all(expected::matches), logged.toString())
        }
    }

    @Test
    fun `filesystem failure diagnostics withhold the transcript path and both request identities`() = runBlocking {
        val path = "/synthetic/$SESSION/$RESPONSE/transcript.jsonl"
        val logged = mutableListOf<String>()
        val failed = TranscriptRequestRoute(
            TranscriptMessageSource { _, _, _ -> throw AccessDeniedException(path) },
            roots,
            enabled,
            Dispatchers.Unconfined,
            log = LogSink { logged += it },
        )
        val reply = failed.read("kimi", SESSION, RESPONSE)
        assertEquals(500, reply.status.value)
        assertEquals(1, logged.size)
        assertTrue(logged.single().matches(Regex(".* at TranscriptRequestRouteTest\\.kt:\\d+")))
        listOf(path, SESSION, RESPONSE).forEach { privateValue ->
            assertFalse(
                logged.single().contains(privateValue),
                "a transcript filesystem failure must withhold identity",
            )
            assertFalse(reply.body.contains(privateValue), "the client error must also remain private")
        }
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
