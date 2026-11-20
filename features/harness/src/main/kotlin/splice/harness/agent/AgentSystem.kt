// NEW: a mode's guidance placed in a request (docs/specs/harness.md, decision 12, Modes): once, at the end of the
// system text, the same bytes on every request, the client's own system bytes unchanged.
package splice.harness.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class AgentSystem {
    /** [body] with [texts] at the end of its system text, in order: one more text block each after the client's last
     *  when system is blocks (no cache mark), appended after a blank line when it is a string, the whole system when
     *  there is none. */
    fun placed(body: JsonObject, texts: List<String>): JsonObject {
        if (texts.isEmpty()) return body
        val system = when (val held = body[SYSTEM_TEXT_KEY]) {
            is JsonArray -> JsonArray(held + texts.map(::block))
            is JsonObject, is JsonNull, null -> JsonPrimitive(texts.joinToString(PROMPT_BREAK))
            is JsonPrimitive -> JsonPrimitive((listOf(held.content) + texts).joinToString(PROMPT_BREAK))
        }
        return JsonObject(body + (SYSTEM_TEXT_KEY to system))
    }

    private fun block(text: String): JsonObject = buildJsonObject {
        put(BLOCK_TYPE, BLOCK_TEXT)
        put(BLOCK_TEXT, text)
    }
}

private const val SYSTEM_TEXT_KEY = "system"
private const val BLOCK_TYPE = "type"
private const val BLOCK_TEXT = "text"
private const val PROMPT_BREAK = "\n\n"
