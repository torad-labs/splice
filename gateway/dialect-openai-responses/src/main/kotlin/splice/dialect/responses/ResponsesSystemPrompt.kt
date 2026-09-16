// NEW: v0.4.0 (operator ask 2026-09-15) — the head's standing system prompt on the responses wire.
// Sibling of ResponsesCompactionTail (tail append, every existing item byte-identical), with the
// item's role being the one difference: a system prompt rides as `developer`, the role the
// responses dialect already uses for base instructions (ResponsesLite.kt).
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.prompt.SystemPromptMode
import splice.core.util.JsonScalars

/** Appends one developer input item after the client-built input, or replaces the client's system
 *  text. Only the input array's closing edge moves, so every existing item — including lite's
 *  leading developer base-instructions item — stays byte-identical. */
public class ResponsesSystemPrompt {
    public fun apply(request: JsonObject, text: String, mode: SystemPromptMode): JsonObject {
        if (text.isEmpty()) return request
        val input = request["input"] as? JsonArray ?: return request
        return if (mode == SystemPromptMode.REPLACE) replaced(request, input, text) else appended(request, input, text)
    }

    private fun appended(request: JsonObject, input: JsonArray, text: String): JsonObject =
        JsonObject(request.toMutableMap().apply { put("input", JsonArray(input + developerItem(text))) })

    /** Substitutes the client's system text wherever the dialect placed it. A lite turn moved it
     *  into a leading developer item (the top-level field is omitted, or carries codex's empty
     *  serde-parity string that never held client text), so the item is what gets rewritten —
     *  including its `additional_tools` neighbour untouched. Everything else rewrites the
     *  top-level `instructions` field the non-lite shape uses. */
    private fun replaced(request: JsonObject, input: JsonArray, text: String): JsonObject {
        val base = input.indexOfFirst { isBaseInstructions(it) }
        val fields = request.toMutableMap()
        if (base < 0) {
            fields[INSTRUCTIONS] = JsonPrimitive(text)
            return JsonObject(fields)
        }
        fields["input"] = JsonArray(
            input.mapIndexed { index, item ->
                if (index == base) {
                    JsonObject((item as JsonObject).toMutableMap().apply { put(CONTENT, JsonPrimitive(text)) })
                } else {
                    item
                }
            },
        )
        return JsonObject(fields)
    }

    /** The base-instructions item the lite shape builds: a developer item whose content is text.
     *  `additional_tools` is also a developer item but carries tools, never a string content. */
    private fun isBaseInstructions(item: JsonElement): Boolean {
        val message = item as? JsonObject ?: return false
        if (JsonScalars.str(message, ROLE) != DEVELOPER) return false
        val content = message[CONTENT]
        return content is JsonPrimitive && content.isString
    }

    /** The developer item's proven shape on this wire: ResponsesLite builds the lite base
     *  instructions with a plain string content, accepted by the live backend. */
    private fun developerItem(text: String): JsonObject = buildJsonObject {
        put(ROLE, DEVELOPER)
        put(CONTENT, text)
    }
}

private const val INSTRUCTIONS = "instructions"
private const val ROLE = "role"
private const val CONTENT = "content"
private const val DEVELOPER = "developer"
