package splice.dialect.responses.request

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

class ResponsesAssistantPhaseFragmentCacheTest {
    @Test
    fun `phase inference does not reuse a cached message from another tool placement`() {
        val builder = ResponsesRequestBuilder(
            ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks()),
        )

        assertEquals("final_answer", assistantPhase(builder, ANSWER_BODY))
        assertEquals("commentary", assistantPhase(builder, TOOL_BODY))
    }

    @Test
    fun `system and developer items close an inferred assistant phase`() {
        listOf("system", "developer").forEach { role ->
            val builder = ResponsesRequestBuilder(
                ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks()),
            )
            assertEquals("final_answer", assistantPhase(builder, contextBody(role)), role)
        }
    }

    private fun assistantPhase(builder: ResponsesRequestBuilder, body: String): String? {
        val parsed = AnthropicParse.parseAnthropicBody(body)
        val request = builder.build(parsed.typed, parsed.raw, options()).req
        val assistant = request["input"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["role"]?.jsonPrimitive?.content == "assistant" }
        return assistant["phase"]?.jsonPrimitive?.content
    }

    private fun options(): BuildOptions = BuildOptions(
        compact = false,
        models = ModelIds(original = "claude-codex--gpt-4.1", upstream = "gpt-4.1"),
        reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.OFF),
        handoff = ReasoningHandoff(replay = InjectPriorReasoning(false), decode = { null }),
    )
}

private fun contextBody(role: String): String = """{"model":"m","messages":[
    {"role":"user","content":"Go."},
    {"role":"assistant","content":"I'll inspect it."},
    {"role":"$role","content":"Use this context."},
    {"role":"assistant","content":[
        {"type":"tool_use","id":"call_1","name":"Read","input":{}}
    ]}
]}"""

private const val ANSWER_BODY = """{"model":"m","messages":[
    {"role":"user","content":"Go."},
    {"role":"assistant","content":"I'll inspect it."}
]}"""

private const val TOOL_BODY = """{"model":"m","messages":[
    {"role":"user","content":"Go."},
    {"role":"assistant","content":"I'll inspect it."},
    {"role":"assistant","content":[
        {"type":"tool_use","id":"call_1","name":"Read","input":{}}
    ]}
]}"""
