// replay_reasoning=false: reasoning from before the last real user prompt stays out of the request, the
// current turn's rounds keep theirs, and replay on sends everything as before.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
import splice.dialect.responses.reasoning.RequestEncryptedReasoning

class ResponsesPriorReasoningTest {
    private val quirks = ResponsesQuirks(providerTag = "claudex", lite = ResponsesLiteQuirks())

    private fun options(replay: Boolean) = BuildOptions(
        compact = false,
        models = ModelIds(original = "claude-codex--gpt-5.6-sol", upstream = "gpt-5.6-sol"),
        reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.TEXT),
        handoff = ReasoningHandoff(
            replay = InjectPriorReasoning(replay),
            includeEncrypted = RequestEncryptedReasoning(true),
            decode = { data ->
                buildJsonObject {
                    put("type", JsonPrimitive("reasoning"))
                    put("id", JsonPrimitive("rs_$data"))
                    put("encrypted_content", JsonPrimitive(data))
                }
            },
            // every client call was made after one cached reasoning item named for it
            lookup = { id -> listOf(id) },
        ),
    )

    /** Two turns. The first ran two rounds; the second, the one in flight, has run two rounds as well. */
    private val transcript = """{"model":"m","messages":[
        {"role":"user","content":"first prompt"},
        {"role":"assistant","content":[{"type":"tool_use","id":"old1","name":"run","input":{}}]},
        {"role":"user","content":[{"type":"tool_result","tool_use_id":"old1","content":"ok"}]},
        {"role":"assistant","content":[{"type":"tool_use","id":"old2","name":"run","input":{}}]},
        {"role":"user","content":[{"type":"tool_result","tool_use_id":"old2","content":"ok"}]},
        {"role":"assistant","content":[{"type":"text","text":"done"}]},
        {"role":"user","content":"second prompt"},
        {"role":"assistant","content":[{"type":"tool_use","id":"now1","name":"run","input":{}}]},
        {"role":"user","content":[{"type":"tool_result","tool_use_id":"now1","content":"ok"}]},
        {"role":"assistant","content":[{"type":"tool_use","id":"now2","name":"run","input":{}}]},
        {"role":"user","content":[{"type":"tool_result","tool_use_id":"now2","content":"ok"}]}
    ]}"""

    private fun sent(options: BuildOptions): Pair<List<String>, List<String>> {
        val parsed = AnthropicParse.parseAnthropicBody(transcript)
        val input = ResponsesRequestBuilder(quirks).build(parsed.typed, parsed.raw, options).req["input"]!!
            .jsonArray.map(kotlinx.serialization.json.JsonElement::jsonObject)
        val reasoning = input.filter { it.type() == "reasoning" }.map { it["id"]!!.jsonPrimitive.content }
        val calls = input.filter { it.type() == "function_call" }.map { it["call_id"]!!.jsonPrimitive.content }
        return reasoning to calls
    }

    private fun JsonObject.type() = this["type"]?.jsonPrimitive?.content

    @Test
    fun `with replay off an earlier turn's reasoning is absent and this turn's rounds keep theirs`() {
        val (reasoning, calls) = sent(options(replay = false))
        assertEquals(listOf("rs_now1", "rs_now2"), reasoning)
        assertEquals(listOf("old1", "old2", "now1", "now2"), calls)
    }

    @Test
    fun `with replay on every turn's reasoning rides back`() {
        val (reasoning, _) = sent(options(replay = true))
        assertEquals(listOf("rs_old1", "rs_old2", "rs_now1", "rs_now2"), reasoning)
    }
}
