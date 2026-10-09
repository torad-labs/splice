// NEW: exact pre-optimization cache identity and request bytes pin the preparation contract.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.util.JsonWire
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.PromptCachePolicy
import splice.dialect.responses.ResponsesBackendQuirks
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning

class ResponsesPreparationGoldenTest {
    @Test
    fun `cache key and all request bytes match the preparation baseline`() {
        val quirks = ResponsesQuirks(
            providerTag = "synthetic",
            backend = ResponsesBackendQuirks(
                promptCache = PromptCachePolicy(key = CacheKeyStrategy.FIRST_MESSAGE_HASH),
                sendClientMetadata = true,
            ),
            lite = ResponsesLiteQuirks(
                responsesLiteModelRegex = Regex("gpt-6"),
            ),
        )
        val options = BuildOptions(
            compact = false,
            models = ModelIds(
                original = "synthetic",
                upstream = "gpt-6.1-sol",
            ),
            reasoning = RequestedReasoning(
                effort = "high",
                summary = "detailed",
                display = ReasoningDisplay.TEXT,
            ),
            handoff = ReasoningHandoff(
                replay = InjectPriorReasoning(false),
                decode = { JsonObject(emptyMap()) },
            ),
        )
        val parsed = AnthropicParse.parseAnthropicBody(GOLDEN_BODY)
        val built = ResponsesRequestBuilder(quirks).build(parsed.typed, parsed.raw, options)
        assertEquals(GOLDEN_KEY, built.meta.scope.conversationKey)
        assertEquals(GOLDEN_REQUEST, JsonWire.string(built.req))
    }
}

private const val GOLDEN_BODY = """{"model":"synthetic","stream":true,"system":"synthetic harness","tools":[{"name":"Read","input_schema":{"type":"object","properties":{"amount":{"enum":[1E2,1.00,-0]}}}}],"messages":[{"role":"assistant","content":"prior"},{"role":"user","content":[{"type":"text","text":"alpha"},{"type":"text","text":""},{"type":"text","text":"café 🧪\n\"\\slash"}]},{"role":"user","content":"later"}]}"""
private const val GOLDEN_KEY = "splice-e0bb99d90f620053052ec6f514467291"
private const val GOLDEN_REQUEST = """{"model":"gpt-6.1-sol","input":[{"type":"additional_tools","role":"developer","tools":[{"type":"namespace","name":"functions","description":"","tools":[{"type":"function","name":"Read","description":"","parameters":{"type":"object","properties":{"amount":{"enum":[100.0,1.0,0]}}}}]}]},{"role":"developer","content":"synthetic harness"},{"role":"assistant","phase":"final_answer","content":"prior"},{"role":"user","content":"alpha"},{"role":"user","content":""},{"role":"user","content":"café 🧪\n\"\\slash"},{"role":"user","content":"later"}],"store":false,"stream":true,"include":["reasoning.encrypted_content"],"prompt_cache_key":"splice-e0bb99d90f620053052ec6f514467291","tool_choice":"auto","parallel_tool_calls":false,"reasoning":{"effort":"high","summary":"detailed","context":"all_turns"},"client_metadata":{"client":"splice","thread_id":"splice-e0bb99d90f620053052ec6f514467291"}}"""
