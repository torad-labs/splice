// NEW: v0.4.0 FEATURES.md §7 — the custom compaction text on the anthropic passthrough wire, appended as one
// text block to the last user message.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** Appends a text block to the last user message. Array content keeps every existing block at the
 *  same byte prefix; string content is promoted to its equivalent one-text-block array first. */
public class PassthroughCompactionTail {
    private val userContent = PassthroughUserContent()

    public fun append(request: JsonObject, text: String): JsonObject {
        if (text.isEmpty()) return request
        val messages = request["messages"] as? JsonArray
        return messages?.let { appendToLastUser(it, text) }
            ?.let { updated -> JsonObject(request.toMutableMap().apply { put("messages", updated) }) }
            ?: request
    }

    private fun appendToLastUser(messages: JsonArray, text: String): JsonArray? {
        val index = messages.indexOfLast { item ->
            JsonScalars.str(item as? JsonObject, "role") == "user"
        }
        val message = messages.getOrNull(index) as? JsonObject ?: return null
        val content = userContent.extended(message["content"], text) ?: return null
        val updatedMessage = JsonObject(message.toMutableMap().apply { put("content", content) })
        return JsonArray(messages.mapIndexed { position, item -> if (position == index) updatedMessage else item })
    }
}
