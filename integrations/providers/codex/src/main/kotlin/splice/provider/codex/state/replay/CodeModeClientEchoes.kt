package splice.provider.codex.state.replay

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.request.ResponsesContextMessage
import splice.provider.codex.CODE_MODE_FIELD_ROLE
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.CodeModeDeliveredEchoes
import splice.provider.codex.history.CodeModeOwnership
import splice.provider.codex.history.CodeModeScriptPlacements

/** Locates delivered prose without consuming callbacks, context or unrelated client content. */
internal class CodeModeClientEchoes(
    private val codec: CodexCodeModeHistoryCodec,
    private val ownership: CodeModeOwnership,
    scripts: CodeModeScriptPlacements,
) {
    private val delivered = CodeModeDeliveredEchoes(codec, scripts)

    fun indexes(
        items: List<JsonElement>,
        boundary: Int,
        record: CodeModeRecord,
        candidateMedia: Map<String, List<JsonElement>> = emptyMap(),
    ): Set<Int> {
        val tailEcho = delivered.indexes(items, record, boundary)
        if (tailEcho.isNotEmpty()) return tailEcho
        if (record.carry.continuity.isNotEmpty() && codec.continuityAt(items, boundary, record.carry.continuity)) {
            return (boundary until boundary + record.carry.continuity.size).toSet()
        }
        val expected = record.issued.mapNotNull { step ->
            step.deliveredText?.takeUnless(String::isEmpty)?.let {
                ResponsesAssistantText.item(it, AssistantPhase.COMMENTARY)
            }
        }
        return if (expected.isEmpty()) emptySet() else echoedIndexes(items, boundary, record, expected, candidateMedia)
    }

    fun skippable(
        item: JsonElement,
        index: Int,
        record: CodeModeRecord,
        owned: Set<String>,
        followUps: Set<Int>,
    ): Boolean {
        val callback = ownership.isCallback(item, owned) || ownership.isOpaque(item, record.origin.outerCallId)
        return callback || index in followUps || isSystemMessage(item)
    }

    fun isSystemMessage(element: JsonElement): Boolean {
        val item = element as? JsonObject
        val message = codec.string(item, CODE_MODE_FIELD_TYPE) in setOf("", "message")
        return (message && codec.string(item, CODE_MODE_FIELD_ROLE) == ResponsesContextMessage.CLIENT_ROLE) ||
            ResponsesContextMessage.isContext(item)
    }

    private fun echoedIndexes(
        items: List<JsonElement>,
        boundary: Int,
        record: CodeModeRecord,
        expected: List<JsonElement>,
        candidateMedia: Map<String, List<JsonElement>>,
    ): Set<Int> {
        val followUps = ownership.followUps(items, record, candidateMedia)
        val owned = record.clientIds()
        val matched = linkedSetOf<Int>()
        for (at in boundary until items.size) {
            if (codec.continuityAt(items, at, listOf(expected[matched.size]))) {
                matched += at
                if (matched.size == expected.size) return matched
            } else if (!skippable(items[at], at, record, owned, followUps)) {
                return emptySet()
            }
        }
        return emptySet()
    }
}
