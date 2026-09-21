// NEW: v0.4.0 (operator ask 2026-09-15) — the head's standing system prompt on the responses wire.
// Sibling of ResponsesCompactionTail (tail append, every existing item byte-identical), with the
// item's role being the one difference: a system prompt rides as `developer`, the role the
// responses dialect already uses for base instructions (ResponsesLite.kt).
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.prompt.ParagraphStrip
import splice.core.prompt.Stripped
import splice.core.prompt.SystemPromptMode
import splice.core.util.JsonScalars

/** Appends one developer input item after the client-built input, replaces the client's system
 *  text, or (STRIP, V4-170) deletes paragraphs from it where it sits. In append only the input
 *  array's closing edge moves, so every existing item — including lite's leading developer
 *  base-instructions item — stays byte-identical. */
public class ResponsesSystemPrompt {
    public fun apply(request: JsonObject, text: String, mode: SystemPromptMode): JsonObject {
        if (text.isEmpty()) return request
        val input = request[INPUT] as? JsonArray ?: return request
        return when (mode) {
            SystemPromptMode.REPLACE -> replaced(request, input, text)
            SystemPromptMode.STRIP -> stripped(request, input, ParagraphStrip(text, "responses"))
            SystemPromptMode.APPEND -> appended(request, input, text)
        }
    }

    /** THE UNION, NOT THE EITHER/OR (V4-172). `replaced` rewrites the lite base developer item when
     *  there is one and the top-level `instructions` otherwise, and the first cut of this method
     *  copied that rule — but `isBaseInstructions` matches "a developer item whose content is a
     *  string", which is EXACTLY the shape [appended] builds. On a non-lite turn an append layer
     *  followed by a strip layer therefore stripped splice's OWN item and left the client's
     *  `instructions` untouched: the documented case in `config/splice.example.toml` failing
     *  silently. A strip edits whatever system text is present at its point, so it edits BOTH.
     *
     *  A text no pattern touches is left exactly as it was; an item stripped to nothing is dropped,
     *  the way the passthrough and chat seams drop an emptied block or message. */
    private fun stripped(request: JsonObject, input: JsonArray, strip: ParagraphStrip): JsonObject {
        val fields = request.toMutableMap()
        var removed = 0
        val instructions = JsonScalars.str(request, INSTRUCTIONS)?.let(strip::strip)
        if (instructions != null && instructions.removed > 0) {
            removed += instructions.removed
            fields[INSTRUCTIONS] = JsonPrimitive(instructions.text)
        }
        val items = input.mapNotNull { item ->
            val after = strippedItem(item, strip) ?: return@mapNotNull item
            removed += after.removed
            if (after.text.isEmpty()) null else rewrittenItem(item as JsonObject, after.text)
        }
        if (removed == 0) return request
        fields[INPUT] = JsonArray(items)
        return JsonObject(fields)
    }

    /** The strip of one base-instructions item, or null when this item is not one or no pattern
     *  touched it — either way the item rides on unchanged. */
    private fun strippedItem(item: JsonElement, strip: ParagraphStrip): Stripped? {
        if (!isBaseInstructions(item)) return null
        val text = JsonScalars.str(item as JsonObject, CONTENT) ?: return null
        return strip.strip(text).takeIf { it.removed > 0 }
    }

    private fun rewrittenItem(item: JsonObject, text: String): JsonObject =
        JsonObject(item.toMutableMap().apply { put(CONTENT, JsonPrimitive(text)) })

    private fun appended(request: JsonObject, input: JsonArray, text: String): JsonObject =
        JsonObject(request.toMutableMap().apply { put(INPUT, JsonArray(input + developerItem(text))) })

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
        fields[INPUT] = JsonArray(
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
private const val INPUT = "input"
private const val ROLE = "role"
private const val CONTENT = "content"
private const val DEVELOPER = "developer"
