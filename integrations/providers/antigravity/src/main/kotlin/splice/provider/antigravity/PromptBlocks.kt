// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). Desktop's message content as the prompt
// blocks of ACP's `session/prompt`: a typed message is one text block, and a message of blocks keeps its text blocks
// and, when agy said in `initialize` that its prompts take them, its images. Anything else in it is Claude Code's
// alone.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars

/** Builds prompt blocks for an agy whose prompts take [images] or not. */
internal class PromptBlocks(private val images: Boolean) {
    fun of(content: JsonElement): JsonArray = buildJsonArray {
        when (content) {
            is JsonPrimitive -> add(text(JsonScalars.str(content).orEmpty()))
            is JsonArray -> content.forEach { item -> (item as? JsonObject)?.let { block(it) }?.let { add(it) } }
            is JsonObject -> Unit
        }
    }

    private fun block(block: JsonObject): JsonObject? = when (JsonScalars.str(block, "type")) {
        "text" -> text(JsonScalars.str(block, "text").orEmpty())
        "image" -> if (images) image(block["source"] as? JsonObject) else null
        else -> null
    }

    private fun text(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun image(source: JsonObject?): JsonObject? {
        val data = JsonScalars.str(source, "data") ?: return null
        return buildJsonObject {
            put("type", "image")
            put("mimeType", JsonScalars.str(source, "media_type") ?: "image/png")
            put("data", data)
        }
    }
}
