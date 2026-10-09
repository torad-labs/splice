// A lite turn never sends a `system` input item. Claude Code sends a peer's message, a
// task notification or hook output as role=system; codex-rs sends mid-conversation context as a
// developer message (core/src/context/hook_additional_context.rs:21) and no system item at all.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

private const val PEER = "Another Claude session sent a message: the build is green."

private const val BODY = """{"model":"claude-codex--gpt-6-sol","stream":true,"max_tokens":1024,
    "system":"You are Splice.",
    "messages":[
      {"role":"user","content":"Fix the bug."},
      {"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"Read","input":{"path":"a.kt"}}]},
      {"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"val x = 1"}]},
      {"role":"system","content":"$PEER"},
      {"role":"user","content":"Keep going."}]}"""

class ResponsesContextMessageRoleTest {
    @Test
    fun `a lite turn sends Claude Code's system message as a typed developer message`() {
        val input = input("gpt-6-sol")
        assertTrue(input.none { it["role"]?.jsonPrimitive?.content == "system" }, input.toString())
        val peer = input.single { it["content"]?.jsonPrimitive?.content == PEER }
        assertEquals(ResponsesContextMessage.item(PEER), peer)
        assertTrue(ResponsesContextMessage.isContext(peer))
        val preamble = input.first { it["role"]?.jsonPrimitive?.content == "developer" }
        assertTrue(!ResponsesContextMessage.isContext(preamble), "the lite preamble is untyped: $preamble")
    }

    @Test
    fun `a non-lite turn keeps the role the client sent`() {
        val peer = input("gpt-5.5").single { it["content"]?.jsonPrimitive?.content == PEER }
        assertEquals("system", peer.getValue("role").jsonPrimitive.content)
    }

    private fun input(upstreamModel: String): List<JsonObject> {
        val parsed = AnthropicParse.parseAnthropicBody(BODY)
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
        return ResponsesRequestBuilder(LITE).build(parsed.typed, parsed.raw, opts).req["input"]!!.jsonArray
            .map { it.jsonObject }
    }
}
