// NEW: v0.4.0 (operator ask 2026-09-15) — the head's standing system prompt on the anthropic
// passthrough wire. Mirrors PassthroughCompactionTail: normalizes the client's `system`, and in
// APPEND mode leaves every existing block at the same byte prefix so the prompt cache keeps
// hitting; REPLACE substitutes the field outright.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.prompt.SystemPromptMode

/** Places a text block in the request's `system` field: APPEND adds it after the client's blocks,
 *  REPLACE makes it the field. An absent (or explicitly null) system normalizes to an empty block
 *  list, and a shape this dialect cannot extend leaves the request untouched. */
public class PassthroughSystemPrompt {
    public fun apply(request: JsonObject, text: String, mode: SystemPromptMode): JsonObject {
        if (text.isEmpty()) return request
        return if (mode == SystemPromptMode.REPLACE) replaced(request, text) else appended(request, text)
    }

    /** Every existing block keeps its exact position and bytes — including any `cache_control`
     *  breakpoint — and the prompt is one trailing text block after them. */
    private fun appended(request: JsonObject, text: String): JsonObject {
        val blocks = when (val system = request["system"]) {
            null, is JsonNull -> JsonArray(emptyList())
            is JsonArray -> system
            is JsonPrimitive -> if (system.isString) {
                JsonArray(listOf(textBlock(system.content)))
            } else {
                return request
            }
            else -> return request
        }
        return JsonObject(request.toMutableMap().apply { put("system", JsonArray(blocks + textBlock(text))) })
    }

    /** The client's whole system field is replaced, so its blocks are GONE from the wire. Always
     *  placeable: overwriting a field needs no shape the client has to have provided. */
    private fun replaced(request: JsonObject, text: String): JsonObject =
        JsonObject(request.toMutableMap().apply { put("system", JsonArray(listOf(textBlock(text)))) })

    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }
}
