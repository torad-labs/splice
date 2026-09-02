// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: the stateless frame
// readers (index keys, scalar coercion, the two terminal-object predicates) and the shared
// wire-field vocabulary the reducer and fold files dispatch on.
package splice.dialect.responses

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import splice.core.turn.ModelTextPicker
import splice.core.util.JsonScalars

// Shared wire-field vocabulary — internal so the reducer and the two fold files read one source
// instead of copying the literals.
internal const val OUTPUT_INDEX = "output_index"
internal const val DELTA = "delta"
internal const val TOOL_SEARCH_CALL = "tool_search_call"

// Leave the positive int space for message/tool output_index; reasoning lives above.
private const val REASONING_KEY_BASE = 1_000_000

/**
 * The stateless frame readers: index keys, scalar coercion and the two terminal-object predicates.
 */
internal class ResponsesFrameParse {

    /** Leave the positive int space for message/tool output_index; reasoning lives above. */
    fun reasoningKey(outputIndex: Int): Int = REASONING_KEY_BASE + outputIndex

    fun intOr(el: JsonElement?): Int? = JsonScalars.str(el)?.toIntOrNull()

    /** CX-01: null when [text] is valid non-empty tool-argument JSON, else the reason. */
    fun invalidToolArgsReason(text: String): String? {
        if (text.isBlank()) return "empty arguments for an opened tool call"
        return try {
            Json.parseToJsonElement(text).run { null }
        } catch (ignored: SerializationException) {
            "malformed JSON"
        } catch (ignored: IllegalArgumentException) {
            "malformed JSON"
        }
    }

    /**
     * The terminal object's text replaces the streamed buffer when the stream produced nothing, or
     * when it produced only weak "no model text returned" filler that the harvested text improves on.
     */
    fun shouldPreferHarvestedText(current: CharSequence, harvested: String): Boolean {
        if (harvested.isEmpty()) return false
        return current.isEmpty() ||
            (ModelTextPicker.isWeakSummaryText(current.toString()) && !ModelTextPicker.isWeakSummaryText(harvested))
    }
}
