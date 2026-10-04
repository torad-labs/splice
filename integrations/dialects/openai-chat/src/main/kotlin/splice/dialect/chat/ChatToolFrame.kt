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

    internal val indexCount: Int get() = toolIndexById.size

    /** A delta-shape tool_calls[i] entry, already resolved to its index and pulled apart. */
    internal data class ParsedCall(val index: Int, val id: String, val name: String, val args: String)

    internal fun parse(tc: JsonObject): ParsedCall {
        val index = resolveToolIndex(tc)
        val fn = tc["function"] as? JsonObject
        return ParsedCall(
            index = index,
            id = JsonScalars.strOrEmpty(tc["id"]),
            name = JsonScalars.strOrEmpty(fn?.get("name")),
            args = JsonScalars.strOrEmpty(fn?.get("arguments")),
        )
    }

    /** Explicit index wins (OpenAI streaming); otherwise each distinct id gets a synthesized
     *  slot, and an id-less index-less call gets a fresh slot per event (complete-call shape). */
    private fun resolveToolIndex(tc: JsonObject): Int {
        (tc["index"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { return it }
        val id = JsonScalars.strOrEmpty(tc["id"])
        if (id.isEmpty()) return nextSynthToolIndex++
        return toolIndexById.getOrPut(id) { nextSynthToolIndex++ }
    }
}
