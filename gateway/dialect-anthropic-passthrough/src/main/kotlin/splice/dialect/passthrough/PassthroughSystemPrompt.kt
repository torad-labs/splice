// NEW: v0.4.0 (operator ask 2026-09-15) — the head's standing system prompt on the anthropic
// passthrough wire. Mirrors PassthroughCompactionTail: normalizes the client's `system`, and in
// APPEND mode leaves every existing block at the same byte prefix so the prompt cache keeps
// hitting; REPLACE substitutes the field outright; STRIP (V4-170) deletes the paragraphs a pattern
// list names from each of the client's text blocks and leaves everything else where it was.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.prompt.ParagraphStrip
import splice.core.prompt.SystemPromptMode
import splice.core.util.JsonScalars

/** Places a text block in the request's `system` field: APPEND adds it after the client's blocks,
 *  REPLACE makes it the field, STRIP edits the client's blocks in place. An absent (or explicitly
 *  null) system normalizes to an empty block list, and a shape this dialect cannot extend leaves
 *  the request untouched. */
public class PassthroughSystemPrompt {
    public fun apply(request: JsonObject, text: String, mode: SystemPromptMode): JsonObject {
        if (text.isEmpty()) return request
        return when (mode) {
            SystemPromptMode.REPLACE -> replaced(request, text)
            SystemPromptMode.STRIP -> stripped(request, ParagraphStrip(text, "passthrough"))
            SystemPromptMode.APPEND -> appended(request, text)
        }
    }

    /** Every block keeps its position, its `cache_control` and its bytes unless a pattern matched a
     *  paragraph inside it; a block stripped to nothing is dropped. A system no pattern touches — or
     *  a shape that is not text — leaves the request the same instance, so the caller reports the
     *  layer as not applied.
     *
     *  V4-172: a `system` the client sent as a BARE STRING is rewritten as a bare string, not
     *  promoted to a block array. The shape of the field is the very front of the request body, and
     *  a layer whose patterns match on one turn and not the next would otherwise flip it back and
     *  forth — a prefix change on alternating turns, which is exactly the cache this mode exists to
     *  keep.
     */
    private fun stripped(request: JsonObject, strip: ParagraphStrip): JsonObject {
        val system = request[SYSTEM]
        return when {
            system is JsonPrimitive && system.isString -> strippedString(request, system.content, strip)
            system is JsonArray -> strippedBlocks(request, system, strip)
            else -> request
        }
    }

    private fun strippedString(request: JsonObject, text: String, strip: ParagraphStrip): JsonObject {
        val after = strip.strip(text)
        if (after.removed == 0) return request
        return JsonObject(request.toMutableMap().apply { put(SYSTEM, JsonPrimitive(after.text)) })
    }

    private fun strippedBlocks(request: JsonObject, blocks: JsonArray, strip: ParagraphStrip): JsonObject {
        val kept = blocks.map { block -> strippedBlock(block, strip) }
        if (kept.all { it.changed.not() }) return request
        return JsonObject(request.toMutableMap().apply { put(SYSTEM, JsonArray(kept.mapNotNull { it.block })) })
    }

    /** One block after the strip: unchanged when it is not text or no pattern matched, null when it
     *  was stripped to nothing. */
    private fun strippedBlock(block: JsonElement, strip: ParagraphStrip): StrippedBlock {
        val obj = block as? JsonObject ?: return StrippedBlock(block, changed = false)
        val text = JsonScalars.str(obj, TEXT) ?: return StrippedBlock(block, changed = false)
        val after = strip.strip(text)
        return when {
            after.removed == 0 -> StrippedBlock(block, changed = false)
            after.text.isEmpty() -> StrippedBlock(null, changed = true)
            else -> StrippedBlock(rewritten(obj, after.text), changed = true)
        }
    }

    private fun rewritten(block: JsonObject, text: String): JsonObject =
        JsonObject(block.toMutableMap().apply { put(TEXT, JsonPrimitive(text)) })

    /** Every existing block keeps its exact position and bytes — including any `cache_control`
     *  breakpoint — and the prompt is one trailing text block after them. */
    private fun appended(request: JsonObject, text: String): JsonObject {
        val blocks = when (val system = request[SYSTEM]) {
            null, is JsonNull -> JsonArray(emptyList())
            is JsonArray -> system
            is JsonPrimitive -> if (system.isString) {
                JsonArray(listOf(textBlock(system.content)))
            } else {
                return request
            }
            else -> return request
        }
        return JsonObject(request.toMutableMap().apply { put(SYSTEM, JsonArray(blocks + textBlock(text))) })
    }

    /** The client's whole system field is replaced, so its blocks are GONE from the wire. Always
     *  placeable: overwriting a field needs no shape the client has to have provided. */
    private fun replaced(request: JsonObject, text: String): JsonObject =
        JsonObject(request.toMutableMap().apply { put(SYSTEM, JsonArray(listOf(textBlock(text)))) })

    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put(TYPE, TYPE_TEXT)
        put(TEXT, text)
    }
}

/** One client block after a strip: [block] null when it was stripped to nothing. */
private data class StrippedBlock(val block: JsonElement?, val changed: Boolean)
