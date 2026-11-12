// PORT-OF: ChatStreamTranslator.kt @ e2e0d0f — invariants unchanged: the tool-call index-synthesis
// rule (Mistral-shape complete calls, id-less/index-less calls) — a distinct vendor-shape concern
// with its own state — and the raw JsonObject reads that shape it, both moved verbatim.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars

// Synthesized tool slots live far above any real streamed index (OpenAI streams 0..n).
private const val SYNTH_INDEX_BASE = 1_000_000

/** Index-less parallel tool_calls (Mistral-shape backends emit complete calls with no `index`):
 *  each NEW id gets its own synthesized slot — defaulting to 0 folded every parallel call into one
 *  corrupted block (ids/names dropped, arguments concatenated). Also the one place that still reads
 *  the raw delta-shape tool-call JSON, so [ChatToolCalls] never needs [JsonScalars] itself. */
internal class ChatToolFrame {

    private var nextSynthToolIndex = SYNTH_INDEX_BASE
    private val toolIndexById = HashMap<String, Int>()

    // An explicit index's current call: the id that holds it, and the slot its deltas go to once a
    // new call has reused the index.
    private val holderByIndex = HashMap<Int, String>()
    private val slotByIndex = HashMap<Int, Int>()

    internal val indexCount: Int get() = toolIndexById.size + holderByIndex.size + slotByIndex.size

    /** A delta-shape tool_calls[i] entry, already resolved to its index and pulled apart. */
    internal data class ParsedCall(val index: Int, val id: String, val name: String, val args: String)

    internal fun parse(tc: JsonObject): ParsedCall {
        val fn = tc["function"] as? JsonObject
        val id = JsonScalars.strOrEmpty(tc["id"])
        val name = JsonScalars.strOrEmpty(fn?.get("name"))
        return ParsedCall(
            index = resolveToolIndex(tc, id, name),
            id = id,
            name = name,
            args = JsonScalars.strOrEmpty(fn?.get("arguments")),
        )
    }

    /** Explicit index wins (OpenAI streaming), through [explicitSlot]; otherwise each distinct id
     *  gets a synthesized slot, and an id-less index-less call gets a fresh slot per event
     *  (complete-call shape). */
    private fun resolveToolIndex(tc: JsonObject, id: String, name: String): Int {
        (tc["index"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { return explicitSlot(it, id, name) }
        if (id.isEmpty()) return nextSynthToolIndex++
        return toolIndexById.getOrPut(id) { nextSynthToolIndex++ }
    }

    /** The slot for a delta at explicit [index]. A delta naming a function under an id other than the
     *  one holding the index is a NEW call reusing it (an endpoint that sends each parallel call
     *  complete at index 0, Gemini's OpenAI-compatible one among them): it gets a slot of its own,
     *  and the index's id-less deltas follow it from then on. Appended to the open block instead, its
     *  name was dropped and its arguments concatenated onto the first call's, so every attempt ended
     *  on malformed JSON. A different id with no name is the same call's continuation, as before. */
    private fun explicitSlot(index: Int, id: String, name: String): Int {
        val holder = holderByIndex[index]
        val current = slotByIndex[index] ?: index
        if (id.isEmpty() || id == holder) return current
        if (holder == null || name.isEmpty()) {
            holderByIndex.putIfAbsent(index, id)
            toolIndexById.putIfAbsent(id, current)
            return current
        }
        val slot = toolIndexById.getOrPut(id) { nextSynthToolIndex++ }
        holderByIndex[index] = id
        slotByIndex[index] = slot
        return slot
    }
}
