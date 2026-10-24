package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.provider.codex.CODE_MODE_FIELD_CALL_ID
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModePending
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.TYPE_CUSTOM_OUTPUT

/**
 * What a record OWNS inside a client history: its callbacks (function_call / function_call_output
 * under an owned call id), the opaque pair (the outer custom call and its output), and (V4-179)
 * its follow-up sequences — a result id's persisted media items, found VERBATIM and CONTIGUOUS
 * right after that id's function_call_output. Follow-up ownership is by exact position and bytes,
 * not by shape: a user image the client put anywhere else, or one that differs from what was
 * rendered, is the client's own content and stays extra. A legacy id (accepted before media was
 * captured, so absent from the map) owns nothing, and whatever the client's history carries for
 * it stays ordinary content, exactly as before this row.
 */
internal class CodeModeOwnership(private val codec: CodexCodeModeHistoryCodec) {
    /** The indexes a rewrite keeps: everything the record does not own and that is not one of the
     *  [continuity] indexes — or null when owned history sits BEFORE the persisted boundary, which
     *  no rewrite can place. */
    fun retained(items: List<JsonElement>, record: CodeModeRecord, continuity: Set<Int>): Set<Int>? {
        val boundary = record.origin.baseline.logicalCount
        val owned = (record.results.keys + record.progress.pending.map(CodeModePending::clientId)).toSet()
        val ownedFollowUps = followUps(items, record)
        val outerCallId = record.origin.outerCallId
        val ownedItemBefore = items.take(boundary).any { isCallback(it, owned) || isOpaque(it, outerCallId) }
        if (ownedItemBefore || ownedFollowUps.any { it < boundary }) return null
        return items.indices.filter { index ->
            val removable = isCallback(items[index], owned) ||
                isOpaque(items[index], record.origin.outerCallId) ||
                index in ownedFollowUps
            index !in continuity && !removable
        }.toSet()
    }

    /** [candidateMedia]: this turn's follow-ups for results the record has not accepted yet. */
    fun followUps(
        items: List<JsonElement>,
        record: CodeModeRecord,
        candidateMedia: Map<String, List<JsonElement>> = emptyMap(),
    ): Set<Int> {
        val owned = mutableSetOf<Int>()
        items.forEachIndexed { index, element ->
            val expected = expectedAfter(element, record, candidateMedia)
            val end = index + 1 + expected.size
            val present = expected.isNotEmpty() && end <= items.size
            if (present && items.subList(index + 1, end) == expected) owned += (index + 1 until end)
        }
        return owned
    }

    fun isCallback(element: JsonElement, owned: Set<String>): Boolean {
        val item = element as? JsonObject
        return codec.string(item, CODE_MODE_FIELD_CALL_ID) in owned &&
            codec.string(item, CODE_MODE_FIELD_TYPE) in FUNCTION_TYPES
    }

    fun isOpaque(element: JsonElement, outerId: String): Boolean {
        val item = element as? JsonObject
        return codec.string(item, CODE_MODE_FIELD_CALL_ID) == outerId &&
            codec.string(item, CODE_MODE_FIELD_TYPE) in CUSTOM_TYPES
    }

    /** The sequence the record owns right after [element]: a result's own follow-ups after its
     *  function_call_output, or — once a history is already canonical (a second script in the same
     *  turn re-canonicalizes the body the first one posted) — the whole durable sequence after the
     *  record's custom output, so the rewrite removes and re-inserts it rather than doubling it. */
    private fun expectedAfter(
        element: JsonElement,
        record: CodeModeRecord,
        candidateMedia: Map<String, List<JsonElement>>,
    ): List<JsonElement> {
        val item = element as? JsonObject ?: return emptyList()
        val callId = codec.string(item, CODE_MODE_FIELD_CALL_ID)
        return when (codec.string(item, CODE_MODE_FIELD_TYPE)) {
            TYPE_FUNCTION_OUTPUT -> record.accepted.media(callId) ?: candidateMedia[callId].orEmpty()
            TYPE_CUSTOM_OUTPUT ->
                if (callId == record.origin.outerCallId) record.accepted.durableMedia() else emptyList()
            else -> emptyList()
        }
    }
}

private val CUSTOM_TYPES = setOf("custom_tool_call", TYPE_CUSTOM_OUTPUT)
private const val TYPE_FUNCTION_OUTPUT = "function_call_output"
private val FUNCTION_TYPES = setOf("function_call", TYPE_FUNCTION_OUTPUT)
