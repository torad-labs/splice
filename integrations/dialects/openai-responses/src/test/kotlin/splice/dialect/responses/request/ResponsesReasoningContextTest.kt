package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.failure.Decline
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning

class ResponsesReasoningContextTest {
    private val ordinary = ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks())

    @Test
    fun `ordinary public Responses preserves every supported explicit reasoning context`() {
        listOf("auto", "current_turn", "all_turns").forEach { context ->
            val request = build(context, ordinary, "gpt-4.1")
            assertEquals(
                context,
                request["reasoning"]?.jsonObject?.get("context")?.jsonPrimitive?.content,
                context,
            )
        }
    }

    @Test
    fun `lite keeps its provider-owned all-turns policy`() {
        val lite = ordinary.copy(lite = ResponsesLiteQuirks(responsesLiteModelRegex = Regex("gpt-5.6")))

        val request = build("current_turn", lite, "gpt-5.6-sol")

        assertEquals("all_turns", request["reasoning"]?.jsonObject?.get("context")?.jsonPrimitive?.content)
    }

    @Test
    fun `unsupported reasoning context is declined instead of omitted`() {
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"m","reasoning":{"context":"session"},"messages":[{"role":"user","content":"hi"}]}""",
        )
        val cause = ResponsesOutputSchema.refusal(body.typed, body.raw)?.cause

        assertEquals("REASONING_CONTEXT", (cause?.why as? Decline.Untranslatable)?.what?.name)
    }

    private fun build(context: String, quirks: ResponsesQuirks, model: String): JsonObject {
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"m","reasoning":{"context":"$context"},"messages":[{"role":"user","content":"hi"}]}""",
        )
        return ResponsesRequestBuilder(quirks).build(parsed.typed, parsed.raw, options(model)).req
    }

    private fun options(model: String): BuildOptions = BuildOptions(
        compact = false,
        models = ModelIds(original = "claude-openai--$model", upstream = model),
        reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.OFF),
        handoff = ReasoningHandoff(replay = InjectPriorReasoning(false), decode = { null }),
    )
}
