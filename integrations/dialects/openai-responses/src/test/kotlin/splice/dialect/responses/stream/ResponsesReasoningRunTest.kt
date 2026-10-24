// NEW: 2026-10-23 — GPT can answer with many hidden reasoning items in a row (nine before one exec call in turn
// de23cca4b4c3, gpt-6.1-sol), and Claude Code draws each redacted_thinking block as its own empty "Thinking…" row.
// A run of reasoning reaches Claude Code as one block, and the next request sends GPT every item of it, in order.
package splice.dialect.responses.stream

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.parse.AnthropicParse
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnOutcome
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.buildResponsesTestRequest
import splice.dialect.responses.responsesTestTranslator
import splice.upstream.sse.WireSink

private const val RUN = 9

/** What Claude Code is sent, in order; each hidden reasoning block keeps its data. */
private class ReasoningRunSink : WireSink {
    val calls = mutableListOf<String>()
    val hidden = mutableListOf<String>()
    private var next = 0

    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++).also { calls.add("text") }

    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++).also { calls.add("thinking") }

    override suspend fun openTool(id: String, name: String): WireBlockIndex =
        WireBlockIndex(next++).also { calls.add("tool:$id") }

    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit

    override suspend fun closeBlock(index: WireBlockIndex) = Unit

    override suspend fun closeAll() = Unit

    override suspend fun addTextBlock(text: String) = Unit

    override suspend fun addRedactedThinking(data: String) {
        calls.add("hidden")
        hidden.add(data)
    }
}

class ResponsesReasoningRunTest {

    @Test
    fun `a run of hidden reasoning reaches Claude Code as one block before the call it led to`() = runTest {
        val sink = ReasoningRunSink()
        drive(reasoningRun(1..RUN) + call("call_1", RUN) + completed(), sink)

        assertEquals(listOf("thinking", "hidden", "tool:call_1"), sink.calls, "one row for the run: ${sink.calls}")
    }

    @Test
    fun `Claude Code's copy of a run sends GPT every reasoning item once, in order`() = runTest {
        val sink = ReasoningRunSink()
        drive(reasoningRun(1..RUN) + call("call_1", RUN) + completed(), sink)

        val input = nextRequest(sink.hidden, "call_1")
        val reasoning = input.filter { it["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals((1..RUN).map { "rs_$it" }, reasoning.map { it.getValue("id").jsonPrimitive.content })
        assertEquals((1..RUN).map { "e-$it" }, reasoning.map { it.getValue("encrypted_content").jsonPrimitive.content })
        val callAt = input.indexOfFirst { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals(RUN, callAt - input.indexOf(reasoning.first()), "the run sits right before its call: $input")
    }

    @Test
    fun `reasoning on each side of a message goes as one block before each thing that follows it`() = runTest {
        val sink = ReasoningRunSink()
        val message = listOf(
            ev("""{"type":"response.output_item.added","output_index":1,"item":{"type":"message","id":"msg_1"}}"""),
            ev("""{"type":"response.output_text.delta","output_index":1,"delta":"Looking."}"""),
            ev(
                """{"type":"response.output_item.done","output_index":1,"item":{"type":"message","id":"msg_1",""" +
                    """"content":[{"type":"output_text","text":"Looking."}]}}""",
            ),
        )
        val after = reasoningRun(2..3, from = 2, summary = false)
        drive(reasoningRun(1..1) + message + after + call("call_1", 4) + completed(), sink)

        assertEquals(
            listOf("thinking", "hidden", "text", "hidden", "tool:call_1"),
            sink.calls,
            "a block before the message and one before the call: ${sink.calls}",
        )
        val input = nextRequest(sink.hidden, "call_1", text = "Looking.")
        val order = input.mapNotNull { it["id"]?.jsonPrimitive?.content ?: it["role"]?.jsonPrimitive?.content }
        assertEquals(listOf("user", "rs_1", "assistant", "rs_2", "rs_3"), order.take(5), "GPT's own order: $input")
    }

    @Test
    fun `a run the stream ends on still reaches Claude Code`() = runTest {
        val sink = ReasoningRunSink()
        val outcome = drive(reasoningRun(1..2) + completed(), sink)

        assertEquals(listOf("thinking", "hidden"), sink.calls, "one block at the end: ${sink.calls}")
        assertTrue((outcome as TurnOutcome.Success).text.emittedThinking, "the hidden block is content the client saw")
    }

    private suspend fun drive(events: List<JsonObject>, sink: WireSink): TurnOutcome =
        responsesTestTranslator(emitEncryptedReasoning = true) { ReasoningReplay.encodeReasoningEnvelope(it) }
            .driveTurn(events.asFlow(), sink)

    /** GPT's reasoning items [ids] at output indexes from [from], the first with a [summary]. */
    private fun reasoningRun(
        ids: IntRange,
        from: Int = 0,
        summary: Boolean = true,
    ): List<JsonObject> = ids.flatMapIndexed { n, id ->
        val oi = from + n
        val item = """{"type":"reasoning","id":"rs_$id","encrypted_content":"e-$id","summary":[]}"""
        val said = if (n == 0 && summary) {
            listOf(ev("""{"type":"response.reasoning_summary_text.delta","output_index":$oi,"delta":"Weighing it."}"""))
        } else {
            emptyList()
        }
        listOf(ev("""{"type":"response.output_item.added","output_index":$oi,"item":$item}""")) + said +
            ev("""{"type":"response.output_item.done","output_index":$oi,"item":$item}""")
    }

    private fun call(id: String, oi: Int): List<JsonObject> {
        val item = """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}"""
        return listOf(
            ev("""{"type":"response.output_item.added","output_index":$oi,"item":$item}"""),
            ev("""{"type":"response.output_item.done","output_index":$oi,"item":$item}"""),
        )
    }

    private fun completed(): List<JsonObject> = listOf(
        ev("""{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":100,"output_tokens":7}}}"""),
    )

    /** The input GPT is sent when Claude Code answers [callId], with the [hidden] blocks it kept in their places
     *  around [text]: the first before the text, the rest after it. */
    private fun nextRequest(hidden: List<String>, callId: String, text: String? = null): List<JsonObject> {
        val blocks = hidden.map { """{"type":"redacted_thinking","data":"$it"}""" }
        val content = if (text == null) {
            blocks
        } else {
            blocks.take(1) + """{"type":"text","text":"$text"}""" + blocks.drop(1)
        } + """{"type":"tool_use","id":"$callId","name":"Read","input":{}}"""
        val raw = """{"model":"gpt-6.1-sol","tools":[{"name":"Read","input_schema":{"type":"object"}}],"messages":[
            {"role":"user","content":"Fix it."},
            {"role":"assistant","content":[${content.joinToString(",")}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"$callId","content":"A"}]}]}"""
        val request = buildResponsesTestRequest(
            ResponsesQuirks(providerTag = "claudex", lite = ResponsesLiteQuirks()),
            AnthropicParse.parseAnthropicBody(raw),
            model = "gpt-6.1-sol",
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = true,
        )
        return request.getValue("input").jsonArray.map { it.jsonObject }
    }

    private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
}
