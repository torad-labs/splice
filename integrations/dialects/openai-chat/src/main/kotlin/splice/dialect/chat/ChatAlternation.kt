// #397: a message that maps to nothing (an assistant turn of thinking blocks only) leaves its neighbours adjacent, and
// two user or two assistant messages in a row are a 400 on a strict backend. This restores the alternation the client
// sent: when such a gap joins two plain-text messages of one role they become one message, and a user text message
// beside a user message of parts (an image) becomes one message of parts. Tool messages are never merged, and two
// messages are never merged across no gap.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val ROLE_KEY = "role"
private const val CONTENT_KEY = "content"

internal class ChatAlternation {
    private val out = mutableListOf<JsonObject>()
    private var gap = false

    /** One client message's mapped messages, in order; an empty list is the gap. */
    fun accept(emitted: List<JsonObject>) {
        if (emitted.isEmpty()) gap = true
        emitted.forEachIndexed { position, message ->
            val merged = if (gap && position == 0) mergedWithLast(message) else null
            if (merged != null) out[out.lastIndex] = merged else out.add(message)
            gap = false
        }
    }

    fun messages(): List<JsonObject> = out

    private fun mergedWithLast(message: JsonObject): JsonObject? {
        val last = out.lastOrNull() ?: return null
        val before = plainText(last)
        val after = plainText(message)
        val sameRole = before != null && after != null && before.first == after.first
        return if (sameRole) {
            plainMessage(before.first, before.second + "\n\n" + after.second)
        } else {
            mergedParts(last, message)
        }
    }

    /** Two user messages where one holds a parts array (an image rides one): the text one becomes a text part. */
    private fun mergedParts(last: JsonObject, message: JsonObject): JsonObject? {
        val before = userParts(last)
        val after = userParts(message)
        return if (before != null && after != null) {
            buildJsonObject {
                put(ROLE_KEY, "user")
                put(CONTENT_KEY, JsonArray(before + after))
            }
        } else {
            null
        }
    }

    /** The content of a user message holding only a text string or a parts array, as parts; else null. */
    private fun userParts(message: JsonObject): List<JsonElement>? {
        val content = message[CONTENT_KEY]
        val isUser = (message[ROLE_KEY] as? JsonPrimitive)?.content == "user" && message.size == 2
        return when {
            !isUser -> null
            content is JsonArray -> content.toList()
            content is JsonPrimitive && content.isString -> listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", content.content)
                },
            )
            else -> null
        }
    }

    /** (role, text) of a user or assistant message that holds nothing but a text string, else null. */
    private fun plainText(message: JsonObject): Pair<String, String>? {
        val role = (message[ROLE_KEY] as? JsonPrimitive)?.content
        val text = (message[CONTENT_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (message.size != 2 || text == null) return null
        return if (role == "user" || role == "assistant") role to text else null
    }

    private fun plainMessage(role: String, text: String): JsonObject = buildJsonObject {
        put(ROLE_KEY, role)
        put(CONTENT_KEY, text)
    }
}
