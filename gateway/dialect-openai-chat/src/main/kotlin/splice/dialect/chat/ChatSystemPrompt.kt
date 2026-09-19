// NEW: v0.4.0 (operator ask 2026-09-15) — the head's standing system prompt on the openai-chat
// wire, where there is no `system` FIELD: the client's system text rides as role = "system"
// messages. Mirrors ChatCompactionTail, which appends a trailing user message without introducing
// consecutive user messages.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.prompt.SystemPromptMode
import splice.core.util.JsonScalars

/** Inserts ONE system-role message before the first user message — after the client's own leading
 *  system messages — so the insertion point is the same on every turn of a conversation and the
 *  prompt cache keeps hitting. A request whose messages are not an array is left untouched. */
public class ChatSystemPrompt {
    public fun apply(request: JsonObject, text: String, mode: SystemPromptMode): JsonObject {
        if (text.isEmpty()) return request
        val messages = request["messages"] as? JsonArray ?: return request
        val updated = if (mode == SystemPromptMode.REPLACE) replaced(messages, text) else inserted(messages, text)
        return JsonObject(request.toMutableMap().apply { put("messages", updated) })
    }

    private fun inserted(messages: JsonArray, text: String): JsonArray {
        val at = insertionIndex(messages)
        return JsonArray(messages.take(at) + systemMessage(text) + messages.drop(at))
    }

    /** The client's whole system field, gone: every system-role message is dropped and ours takes
     *  the insertion point the append mode would have used. */
    private fun replaced(messages: JsonArray, text: String): JsonArray {
        val kept = messages.filterNot { roleOf(it) == SYSTEM }
        val at = insertionIndex(kept)
        return JsonArray(kept.take(at) + systemMessage(text) + kept.drop(at))
    }

    /** Before the first user message (which is where a chat conversation's system text belongs),
     *  or at the end when the client sent no user message at all. */
    private fun insertionIndex(messages: List<JsonElement>): Int {
        val firstUser = messages.indexOfFirst { roleOf(it) == USER }
        return if (firstUser >= 0) firstUser else messages.size
    }

    private fun roleOf(message: JsonElement): String? = JsonScalars.str(message as? JsonObject, ROLE)

    private fun systemMessage(text: String): JsonObject = buildJsonObject {
        put(ROLE, SYSTEM)
        put("content", text)
    }
}

private const val ROLE = "role"
private const val USER = "user"
private const val SYSTEM = "system"
