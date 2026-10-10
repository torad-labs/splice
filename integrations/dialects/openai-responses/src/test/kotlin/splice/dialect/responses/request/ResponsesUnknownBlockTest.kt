// #398: a content block kind splice does not enumerate (a search result, a server tool result carried over from a
// session that began on another head) cannot ride the Responses wire. It used to vanish without a word; it now leaves
// a marker in the user's turn, the same way an omitted document does, so the model knows content was left out.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.RequestEncryptedReasoning

class ResponsesUnknownBlockTest {

    @Test
    fun `a block kind splice does not enumerate leaves a marker in the input, not a silent drop`() {
        val body = """{"model":"m","max_tokens":1,"messages":[{"role":"user","content":[""" +
            """{"type":"text","text":"look at this"},""" +
            """{"type":"web_search_result","title":"SECRET-RESULT-TITLE","url":"https://example.invalid"}]}]}"""
        val parsed = AnthropicParse.parseAnthropicBody(body)
        val built = ResponsesRequestBuilder(ResponsesQuirks(providerTag = "claudex", lite = ResponsesLiteQuirks()))
            .build(parsed.typed, parsed.raw, options()).req

        val texts = built.getValue("input").jsonArray.map { it.jsonObject.toString() }
        assertTrue(texts.any { it.contains("look at this") }, texts.toString())
        val marker = texts.singleOrNull { it.contains("block omitted by claudex proxy") }
        assertTrue(marker != null, "the unknown block left no marker: $texts")
        assertTrue(marker!!.contains("web_search_result"), marker)
        assertFalse(texts.any { it.contains("SECRET-RESULT-TITLE") }, "the omitted block's content must not ride")
        assertEquals("user", built.getValue("input").jsonArray.last().jsonObject["role"]?.jsonPrimitive?.content)
    }

    private fun options() = BuildOptions(
        compact = false,
        models = ModelIds(original = "claude-codex--gpt-5.6-sol", upstream = "gpt-5.6-sol"),
        reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.OFF),
        handoff = ReasoningHandoff(
            replay = InjectPriorReasoning(false),
            includeEncrypted = RequestEncryptedReasoning(false),
            decode = { data -> buildJsonObject { put("decoded", JsonPrimitive(data)) } },
        ),
        sessionId = null,
    )
}
