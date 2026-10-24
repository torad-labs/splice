package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.parse.AnthropicParse
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.ReasoningDisplay
import splice.core.util.JsonScalars
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.ReasoningCache
import splice.dialect.responses.reasoning.ReasoningCacheFiles
import splice.dialect.responses.reasoning.ReasoningHistoryLookup
import splice.dialect.responses.reasoning.ReasoningPositionIndex
import splice.dialect.responses.reasoning.ResponsesHistoryCursor
import java.nio.file.Path

class ResponsesReasoningFragmentCompatibilityTest(@param:TempDir private val state: Path) {
    private val quirks = ResponsesQuirks(providerTag = "fixture", lite = ResponsesLiteQuirks())
    private val body = AnthropicParse.parseAnthropicBody(
        """{"model":"gpt-5.6-sol","messages":[
            {"role":"user","content":"Start 🚀"},
            {"role":"assistant","content":"Before middle after"},
            {"role":"user","content":"Read now."},
            {"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"run","input":{}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"file contents"}]},
            {"role":"assistant","content":"Complete."},
            {"role":"user","content":"Continue."}]}""",
    )

    @Test
    fun `warm fragments keep newly inserted reasoning boundaries across restart and removal`() {
        val cache = cache()
        val builder = ResponsesRequestBuilder(quirks)
        val baseline = input(builder, cache)
        val cursor = ResponsesHistoryCursor()
        body.typed.messages.first().let { message -> message.content.forEach { cursor.append(message.role, it) } }
        retain(cache, cursor.history.key, "rs_before")
        val warm = input(builder, cache)
        assertEquals(warm, input(builder, cache))
        cursor.text("assistant", "Before ")
        retain(cache, cursor.history.key, "rs_middle")
        val inserted = input(builder, cache)
        assertEquals(
            listOf(
                "user:Start 🚀", "reasoning:rs_before", "assistant:Before ", "reasoning:rs_middle",
                "assistant:middle after", "user:Read now.", "function_call:call_1",
                "function_call_output:call_1", "assistant:Complete.", "user:Continue.",
            ),
            inserted.map(::label),
        )
        assertEquals(inserted, input(builder, cache()))
        assertEquals(inserted, input(ResponsesRequestBuilder(quirks), cache()))
        cache.dropConversation(FRAGMENT_CONVERSATION)
        assertEquals(baseline, input(builder, cache))
    }

    private fun cache(): ReasoningCache = ReasoningCache(files = ReasoningCacheFiles(state) {})

    private fun input(builder: ResponsesRequestBuilder, cache: ReasoningCache): JsonArray = builder.build(
        body.typed,
        body.raw,
        BuildOptions(
            compact = false,
            models = ModelIds("gpt-5.6-sol", "gpt-5.6-sol"),
            reasoning = RequestedReasoning(null, null, ReasoningDisplay.TEXT),
            handoff = ReasoningHandoff(
                replay = InjectPriorReasoning(true),
                decode = { ReasoningReplay.decodeReasoningEnvelope(it) },
                history = ReasoningHistoryLookup {
                    ReasoningPositionIndex().positions(
                        cache.snapshot(FRAGMENT_CONVERSATION),
                    )
                },
            ),
            sessionId = "fragment-fixture",
        ),
    ).req.getValue("input").jsonArray

    private fun retain(cache: ReasoningCache, position: String, id: String) {
        val item = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive(id))
            put("encrypted_content", JsonPrimitive("fixture-$id"))
        }
        val envelope = checkNotNull(ReasoningReplay.encodeReasoningEnvelope(item))
        cache.put(FRAGMENT_CONVERSATION, listOf(ReasoningPositionIndex().index(position, envelope)), listOf(envelope))
    }

    private fun label(value: JsonElement): String {
        val item: JsonObject = value.jsonObject
        return when (val type = JsonScalars.str(item, "type")) {
            "reasoning" -> "reasoning:${JsonScalars.str(item, "id") }"
            "function_call", "function_call_output" -> "$type:${JsonScalars.str(item, "call_id") }"
            else -> "${JsonScalars.str(item, "role") }:" + item.getValue("content").jsonArray.joinToString("") {
                it.jsonObject.getValue("text").jsonPrimitive.content
            }
        }
    }
}

private const val FRAGMENT_CONVERSATION = "splice-fragment-compatibility"
