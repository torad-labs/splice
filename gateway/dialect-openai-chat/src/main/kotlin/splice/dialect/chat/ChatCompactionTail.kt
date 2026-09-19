// NEW: v0.4.0 FEATURES.md §7 — the custom compaction text on the openai-chat wire, after the client's
// summarizer prompt without introducing consecutive user messages.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars

/** Extends a trailing user message, preserving its fields and content prefix. Otherwise appends a
 *  final user message; an absent tail leaves the original request instance untouched. */
public class ChatCompactionTail {
    public fun append(request: JsonObject, text: String): JsonObject {
        if (text.isEmpty()) return request
        val messages = request["messages"] as? JsonArray ?: return request
        val updated = appendToMessages(messages, text)
        return if (updated === messages) {
            request
        } else {
            JsonObject(request.toMutableMap().apply { put("messages", updated) })
        }
    }

    private fun appendToMessages(messages: JsonArray, text: String): JsonArray {
        val last = messages.lastOrNull() as? JsonObject
        return if (last != null && JsonScalars.str(last, "role") == "user") {
            val content = appendedContent(last["content"], text) ?: return messages
            val merged = JsonObject(last.toMutableMap().apply { put("content", content) })
            JsonArray(messages.dropLast(1) + merged)
        } else {
            JsonArray(
                messages + buildJsonObject {
                    put("role", "user")
                    put("content", text)
                },
            )
        }
    }

    private fun appendedContent(content: JsonElement?, text: String): JsonElement? = when {
        content is JsonArray -> JsonArray(
            content + buildJsonObject {
                put("type", "text")
                put("text", text)
            },
        )
        content is JsonPrimitive && content.isString -> {
            val original = JsonScalars.strIfString(content)
            JsonPrimitive(if (original.isEmpty()) text else "$original\n\n$text")
        }
        else -> null
    }
}
