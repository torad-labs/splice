package splice.app.provider.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/** Replays exactly the assistant blocks the client read, and only ordinary Anthropic result fields. */
internal class CapturedMcpConversation(val session: String) {
    private val messages = mutableListOf<JsonObject>()

    suspend fun begin(rig: CapturedMcpRig): JsonObject {
        messages += message("user", JsonPrimitive("run the supplied source"))
        val blocks = rig.send(session, messages)
        messages += message("assistant", JsonArray(blocks))
        val use = blocks.single { it["type"] == JsonPrimitive("tool_use") }
        assertEquals("mcp__capture__inspect", use["name"]?.jsonPrimitive?.content)
        assertTrue(use.getValue("id").jsonPrimitive.content.startsWith("toolu_splice_"))
        return use
    }

    fun withResult(use: JsonObject, raw: JsonObject): List<JsonObject> {
        val result = buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", use.getValue("id"))
            put("content", raw.getValue("content"))
            put("is_error", raw.getValue("isError"))
        }
        assertFalse("structuredContent" in result)
        assertFalse("_meta" in result)
        return messages + message("user", JsonArray(listOf(result)))
    }

    suspend fun finish(rig: CapturedMcpRig, use: JsonObject, raw: JsonObject) {
        val resumed = withResult(use, raw)
        messages.clear()
        messages += resumed
        val blocks = rig.send(session, messages)
        assertTrue(blocks.none { it["type"] == JsonPrimitive("tool_use") }, blocks.toString())
        messages += message("assistant", JsonArray(blocks))
    }

    suspend fun next(rig: CapturedMcpRig) {
        messages += message("user", JsonPrimitive("continue"))
        val blocks = rig.send(session, messages)
        assertTrue(blocks.none { it["type"] == JsonPrimitive("tool_use") }, blocks.toString())
        messages += message("assistant", JsonArray(blocks))
    }

    private fun message(role: String, content: JsonElement): JsonObject = buildJsonObject {
        put("role", role); put("content", content)
    }
}

/** Folds ordinary Anthropic stream blocks, including the input deltas the permission client actually receives. */
internal class CapturedMcpBlocks {
    private val blocks = sortedMapOf<Int, MutableMap<String, JsonElement>>()
    private val inputs = mutableMapOf<Int, StringBuilder>()

    fun take(event: JsonObject) {
        val index = event["index"]?.jsonPrimitive?.intOrNull ?: return
        when (event["type"]?.jsonPrimitive?.content) {
            "content_block_start" -> blocks[index] = event.getValue("content_block").jsonObject.toMutableMap()
            "content_block_delta" -> delta(index, event.getValue("delta").jsonObject)
        }
    }

    private fun delta(index: Int, delta: JsonObject) {
        val block = blocks[index] ?: return
        when (delta["type"]?.jsonPrimitive?.content) {
            "text_delta" -> append(block, "text", delta)
            "thinking_delta" -> append(block, "thinking", delta)
            "signature_delta" -> block["signature"] = delta.getValue("signature")
            "input_json_delta" -> inputs.getOrPut(index) { StringBuilder() }
                .append(delta.getValue("partial_json").jsonPrimitive.content)
        }
    }

    private fun append(block: MutableMap<String, JsonElement>, key: String, delta: JsonObject) {
        block[key] = JsonPrimitive(
            block[key]?.jsonPrimitive?.content.orEmpty() + delta.getValue(key).jsonPrimitive.content,
        )
    }

    fun values(): List<JsonObject> = blocks.map { (index, block) ->
        inputs[index]?.let { block["input"] = Json.parseToJsonElement(it.toString().ifEmpty { "{}" }) }
        JsonObject(block)
    }
}
