package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.failure.Decline
import splice.core.parse.AnthropicParse
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import kotlin.random.Random

// Goal: requested source document annotations are refused, never silently promised as ordinary Responses text.
class ResponsesDocumentAdmissionTest {
    private val quirks = ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks())

    @Test
    fun `generated document requests refuse enabled citations in messages and tool results only`() {
        val random = Random(2601)
        repeat(128) { trial ->
            val flags = List(trial % 7) { listOf<Boolean?>(null, false, true)[random.nextInt(3)] }
            val documents = flags.mapIndexed { index, flag -> document(flag, trial, index) }
            val expected = if (flags.any { it == true }) "DOCUMENT_CITATIONS" else null
            for (toolResult in listOf(false, true)) {
                assertEquals(expected, refusal(request(documents, toolResult)), "case $trial, tool result $toolResult")
            }
        }
    }

    @Test
    fun `citation-looking literal text and unrelated unknown metadata do not request document annotations`() {
        val content = listOf(
            buildJsonObject {
                put("type", "text")
                put("text", "{\"citations\":{\"enabled\":true}}")
            },
            buildJsonObject {
                put("type", "unknown_source_block")
                put("citations", buildJsonObject { put("enabled", true) })
            },
        )
        assertNull(refusal(request(content, false)))
        assertNull(refusal(request(content, true)))
    }

    private fun document(enabled: Boolean?, trial: Int, index: Int): JsonObject = buildJsonObject {
        put("type", "document")
        put("title", "Title \"$trial\" $index 🧭")
        put("context", "Context\n$trial/$index")
        put(
            "source",
            buildJsonObject {
                put("type", "text")
                put("media_type", "text/plain")
                put("data", "Reference $trial/$index")
            },
        )
        put("citations", enabled?.let { buildJsonObject { put("enabled", it) } } ?: JsonNull)
    }

    private fun request(content: List<JsonObject>, toolResult: Boolean): JsonObject {
        val items = listOf(text("Read the reference.")) + content
        val messages = if (toolResult) exchange(JsonArray(items)) else listOf(message("user", items))
        val reader = buildJsonObject {
            put("name", "reader")
            put("input_schema", buildJsonObject { put("type", "object") })
        }
        return buildJsonObject {
            put("model", "m")
            put("max_tokens", 128)
            put("tools", JsonArray(listOf(reader)))
            put("messages", JsonArray(messages))
        }
    }

    private fun exchange(content: JsonArray): List<JsonObject> {
        val call = buildJsonObject {
            put("type", "tool_use")
            put("id", "call_doc")
            put("name", "reader")
            put("input", JsonObject(emptyMap()))
        }
        val result = buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", "call_doc")
            put("content", content)
        }
        return listOf(
            message("user", listOf(text("Read the reference."))),
            message("assistant", listOf(call)),
            message("user", listOf(result)),
        )
    }

    private fun message(role: String, content: List<JsonObject>): JsonObject = buildJsonObject {
        put("role", role)
        put("content", JsonArray(content))
    }

    private fun text(value: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

    private fun refusal(request: JsonObject): String? {
        val parsed = AnthropicParse.parseAnthropicBody(request.toString())
        val cause = ResponsesOutputSchema.refusal(parsed.typed, parsed.raw, quirks)?.cause
        return (cause?.why as? Decline.Untranslatable)?.what?.name
    }
}
