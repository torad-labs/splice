// NEW: V4-335 — an assistant message keeps its phase when splice replays Claude Code's history on the
// Responses path. OpenAI's reasoning guide: "If you replay assistant history manually, preserve each
// original phase value. Missing or dropped phase can cause preambles to be treated as final answers",
// and codex-rs keeps phase on every replayed message (protocol/src/models.rs:1021-1036). Claude Code
// keeps no phase, so the replay reads it off the message's shape: text a tool_use follows in the same
// message was said on the way to that call (commentary), text nothing follows is the answer
// (final_answer). Lite turns only; the non-lite shape stays byte-for-byte what it was.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning

private val LITE = ResponsesQuirks(
    providerTag = "claudex",
    lite = ResponsesLiteQuirks(
        responsesLiteModelRegex = Regex("gpt-5\\.6|gpt-6"),
    ),
)

/** A tool round and its answer: the preamble before the Read, the result, then the answer. */
private const val HISTORY = """{"model":"claude-codex--gpt-6-sol","stream":true,"max_tokens":1024,
    "system":"You are Splice.",
    "messages":[
      {"role":"user","content":"Fix the bug."},
      {"role":"assistant","content":[
        {"type":"text","text":"I'll read the file first."},
        {"type":"tool_use","id":"call_1","name":"Read","input":{"path":"a.kt"}}]},
      {"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"val x = 1"}]},
      {"role":"assistant","content":[{"type":"text","text":"Line 1 is fine; the bug is elsewhere."}]},
      {"role":"user","content":"Keep going."}]}"""

private fun input(body: String, upstreamModel: String): List<JsonObject> {
    val parsed = AnthropicParse.parseAnthropicBody(body)
    val opts = BuildOptions(
        compact = false,
        models = ModelIds(
            original = "claude-codex--$upstreamModel",
            upstream = upstreamModel,
        ),
        reasoning = RequestedReasoning(
            effort = "high",
            summary = "detailed",
            display = ReasoningDisplay.TEXT,
        ),
        handoff = ReasoningHandoff(
            replay = InjectPriorReasoning(false),
            decode = { null },
        ),
    )
    val req = ResponsesRequestBuilder(LITE).build(parsed.typed, parsed.raw, opts).req
    return req["input"]!!.jsonArray.map { it.jsonObject }
}

/** Each message item as role, its phase (or "-"), and its text. */
private fun messages(items: List<JsonObject>): List<String> = items.filter { "role" in it && "type" !in it }.map {
    val phase = it["phase"]?.jsonPrimitive?.content ?: "-"
    "${it["role"]!!.jsonPrimitive.content}|$phase|${it["content"]!!.jsonPrimitive.content}"
}

class ResponsesMessagePhaseTest {

    @Test
    fun `a lite turn replays the preamble before a tool call as commentary and the answer as final_answer`() {
        assertEquals(
            listOf(
                "developer|-|You are Splice.",
                "user|-|Fix the bug.",
                "assistant|commentary|I'll read the file first.",
                "assistant|final_answer|Line 1 is fine; the bug is elsewhere.",
                "user|-|Keep going.",
            ),
            messages(input(HISTORY, "gpt-6-sol")),
        )
    }

    @Test
    fun `text between two tool calls is commentary, and text after the last one is the answer`() {
        val body = """{"model":"m","messages":[
            {"role":"user","content":"Go."},
            {"role":"assistant","content":[
              {"type":"text","text":"Checking."},
              {"type":"tool_use","id":"call_1","name":"Read","input":{}},
              {"type":"text","text":"Then the test."},
              {"type":"tool_use","id":"call_2","name":"Bash","input":{}},
              {"type":"text","text":"Done."}]}]}"""
        val assistant = messages(input(body, "gpt-6-sol")).filter { it.startsWith("assistant") }
        assertEquals(
            listOf(
                "assistant|commentary|Checking.",
                "assistant|commentary|Then the test.",
                "assistant|final_answer|Done.",
            ),
            assistant,
        )
    }

    @Test
    fun `a non-lite turn replays assistant text exactly as before, with no phase`() {
        val assistant = input(HISTORY, "gpt-4.1").filter { it["role"]?.jsonPrimitive?.content == "assistant" }
        assertEquals(
            listOf(
                """{"role":"assistant","content":"I'll read the file first."}""",
                """{"role":"assistant","content":"Line 1 is fine; the bug is elsewhere."}""",
            ),
            assistant.map { it.toString() },
        )
    }
}
