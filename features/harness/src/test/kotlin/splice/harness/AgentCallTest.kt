// The model calls a capability itself, through a tool the client never sees (docs/specs/harness.md, decision 12). Each
// test drives turns through a head whose provider answers with the model's call, and reads what the client's stream
// carried, what the worker was asked and what the provider was sent.
package splice.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.capability.Capability
import splice.core.capability.CapabilityCaller
import splice.core.capability.CapabilityCalling
import splice.core.capability.CapabilityTool
import splice.core.turn.GATHERS_FOR_HEADER
import java.nio.file.Path

class AgentCallTest(@param:TempDir private val tmp: Path) {
    private val rig = CapabilityRig(tmp)

    @AfterEach
    fun close() = rig.close()

    private fun advisor(): Capability = rig.capability("advisor").copy(
        call = CapabilityCalling(
            CapabilityCaller.AGENT,
            tool = CapabilityTool(TOOL, "Ask the advisor about a plan.", INPUT),
        ),
    )

    /** The provider asks the advisor first, then answers once the request carries the call's result. */
    private fun model() {
        rig.messages = { body -> if (TOOL_RESULT in body) stream(text(ANSWER)) else stream(text(ASKING), call()) }
    }

    @Test
    fun `the tool is offered after the client's tools, the same bytes every time, and only on a request with tools`() =
        runBlocking {
            val head = rig.head(listOf(advisor()))

            rig.send(head, Turn("s1", opening("Fix the bug")))
            rig.send(head, Turn("s2", opening("Fix the bug")))
            rig.send(head, Turn("s3", opening("Fix the bug"), tools = false))

            val (one, two, three) = rig.provider.map { Json.parseToJsonElement(it).jsonObject["tools"] }
            val names = one!!.jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertEquals(listOf("Read", TOOL), names)
            assertEquals(OFFERED, one.jsonArray.last())
            assertEquals(one, two)
            assertEquals(null, three, "a request without tools is sent as the client built it")
        }

    @Test
    fun `a Context Getter's own request carries the client's tools and never the capability's`() = runBlocking {
        val head = rig.head(listOf(advisor()))

        rig.send(head, Turn("gatherer", opening("Explore")), headers = mapOf(GATHERS_FOR_HEADER to "s1"))

        val tools = Json.parseToJsonElement(rig.provider.single()).jsonObject.getValue("tools").jsonArray
        assertEquals(listOf("Read"), tools.map { it.jsonObject.getValue("name").jsonPrimitive.content })
    }

    @Test
    fun `the call is answered while the turn is held, and the client gets one message without it`() = runBlocking {
        val head = rig.head(listOf(advisor()))
        model()

        val stream = rig.send(head, Turn("s1", opening("Fix the bug")))

        assertFalse(TOOL in stream, stream)
        assertEquals(1, Regex("event: message_start").findAll(stream).count(), stream)
        assertEquals(1, Regex("event: message_stop").findAll(stream).count(), stream)
        assertTrue(ASKING in stream && ANSWER in stream, stream)
        assertTrue("\"stop_reason\":\"end_turn\"" in stream, stream)

        val asked = rig.asked.single()
        assertEquals(QUESTION, asked.fields.getValue("input"))
        val (first, second) = rig.provider.map { Json.parseToJsonElement(it).jsonObject.getValue("messages").jsonArray }
        assertEquals(first.size + 2, second.size)
        assertEquals(first, JsonArray(second.take(first.size)))
        val result = second.last().jsonObject.getValue("content").jsonArray.single().jsonObject
        assertEquals(CALL_ID, result.getValue("tool_use_id").jsonPrimitive.content)
        assertEquals("advisor found src/main.ts:10-42.", result.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `a worker that fails gives the model a result saying so, and the turn goes on`() = runBlocking {
        val head = rig.head(listOf(advisor()))
        model()
        rig.reply = { WorkerReply(emptyList(), status = 500) }

        val stream = rig.send(head, Turn("s1", opening("Fix the bug")))

        assertTrue(ANSWER in stream, stream)
        val said = Json.parseToJsonElement(rig.provider.last()).jsonObject.getValue("messages").jsonArray.last()
            .jsonObject.getValue("content").jsonArray.single().jsonObject
        assertTrue("failed" in said.getValue("content").jsonPrimitive.content, said.toString())
        assertTrue("500" in said.getValue("content").jsonPrimitive.content, said.toString())
        assertEquals(JsonPrimitive(true), said["is_error"])
    }

    @Test
    fun `the next turn puts the call and its result back where the client holds the answer`() = runBlocking {
        val head = rig.head(listOf(advisor()))
        model()
        rig.send(head, Turn("s1", opening("Fix the bug")))
        val made = Json.parseToJsonElement(rig.provider.last()).jsonObject.getValue("messages").jsonArray
        rig.messages = { null }

        // the client holds one assistant message: the text before the call, then the answer's
        val held = """[{"role":"user","content":[{"type":"text","text":"Fix the bug"}]},""" +
            """{"role":"assistant","content":[{"type":"text","text":"$ASKING"},{"type":"text","text":"$ANSWER"}]},""" +
            """{"role":"user","content":[{"type":"text","text":"go on"}]}]"""
        rig.send(head, Turn("s1", held))

        val sent = Json.parseToJsonElement(rig.provider.last()).jsonObject.getValue("messages").jsonArray
        val answer = buildJsonObject {
            put("role", "assistant")
            put("content", JsonArray(listOf(Json.parseToJsonElement(text(ANSWER).block))))
        }
        assertEquals(JsonArray(made + answer + sent.last()), sent)
    }

    @Test
    fun `a call beside the client's reaches the client as its calls only, and its result goes beside theirs`() =
        runBlocking {
            val head = rig.head(listOf(advisor()))
            rig.messages = { body ->
                if (TOOL_RESULT in body) stream(text(ANSWER)) else stream(text(ASKING), call(), read())
            }

            val stream = rig.send(head, Turn("s1", opening("Fix the bug")))

            assertFalse(TOOL in stream, stream)
            assertTrue(READ_ID in stream && "\"stop_reason\":\"tool_use\"" in stream, stream)
            val made = Json.parseToJsonElement(rig.provider.single()).jsonObject.getValue("messages").jsonArray
            val held = made.toString().dropLast(1) +
                """,{"role":"assistant","content":[{"type":"text","text":"$ASKING"},""" +
                """{"type":"tool_use","id":"$READ_ID","name":"Read","input":{"path":"src/main.ts"}}]},""" +
                """{"role":"user","content":[{"type":"tool_result","tool_use_id":"$READ_ID","content":"fn main"}]}]"""
            rig.send(head, Turn("s1", held))

            val sent = Json.parseToJsonElement(rig.provider.last()).jsonObject.getValue("messages").jsonArray
            val calls = sent[sent.size - 2].jsonObject.getValue("content").jsonArray
                .filter { it.jsonObject["type"] == JsonPrimitive("tool_use") }.map { it.jsonObject.getValue("id") }
            assertEquals(listOf(JsonPrimitive(CALL_ID), JsonPrimitive(READ_ID)), calls, "the model's message, whole")
            val results = sent.last().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
            assertEquals(listOf(CALL_ID, READ_ID), results.map { it.getValue("tool_use_id").jsonPrimitive.content })
            assertEquals("advisor found src/main.ts:10-42.", results.first().getValue("content").jsonPrimitive.content)
        }
}

private const val TOOL = "ask_advisor"
private const val CALL_ID = "toolu_01advisor"
private const val ASKING = "Asking the advisor first."
private const val ANSWER = "The advisor agrees; fixing it now."
private const val TOOL_RESULT = "\"tool_result\""

private val INPUT = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { putJsonObject("question") { put("type", "string") } }
}
private val QUESTION = buildJsonObject { put("question", "Is the fix sound?") }
private val OFFERED = buildJsonObject {
    put("name", TOOL)
    put("description", "Ask the advisor about a plan.")
    put("input_schema", INPUT)
}

/** One block of the model's message: how it starts, and the deltas that fill it. */
private data class Streamed(val block: String, val start: String, val deltas: List<String>)

private fun text(t: String) = Streamed(
    block = """{"type":"text","text":"$t"}""",
    start = """{"type":"text","text":""}""",
    deltas = listOf("""{"type":"text_delta","text":"$t"}"""),
)

private fun call() = Streamed(
    block = "",
    start = """{"type":"tool_use","id":"$CALL_ID","name":"$TOOL","input":{}}""",
    deltas = listOf("""{"type":"input_json_delta","partial_json":${JsonPrimitive(QUESTION.toString())}}"""),
)

/** A Messages stream of [blocks]; it stops for a tool when one of them calls one. */
private fun stream(vararg blocks: Streamed): String {
    val stop = if (blocks.any { "tool_use" in it.start }) "tool_use" else "end_turn"
    val events = mutableListOf("message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":3}}}""")
    blocks.forEachIndexed { at, b ->
        events += "content_block_start" to """{"type":"content_block_start","index":$at,"content_block":${b.start}}"""
        b.deltas.forEach {
            events += "content_block_delta" to """{"type":"content_block_delta","index":$at,"delta":$it}"""
        }
        events += "content_block_stop" to """{"type":"content_block_stop","index":$at}"""
    }
    events += "message_delta" to
        """{"type":"message_delta","delta":{"stop_reason":"$stop"},"usage":{"output_tokens":2}}"""
    events += "message_stop" to """{"type":"message_stop"}"""
    return events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }
}

private fun read() = Streamed(
    block = "",
    start = """{"type":"tool_use","id":"$READ_ID","name":"Read","input":{}}""",
    deltas = listOf("""{"type":"input_json_delta","partial_json":${JsonPrimitive("""{"path":"src/main.ts"}""")}}"""),
)

private const val READ_ID = "toolu_01clientread"
