// NEW: classify client content outside a parked script's owned callbacks and baseline.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesContextMessage
import splice.provider.codex.CODE_MODE_FIELD_ROLE
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeExtra
import splice.provider.codex.CodeModeOwnership
import splice.provider.codex.CodeModePending
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec

internal class CodeModeExtraContent(
    private val codec: CodexCodeModeHistoryCodec,
    private val ownership: CodeModeOwnership,
) {
    private val messageTypes = setOf("", "message")

    /** [input]: the round's parsed input array, or null when the round is not a Responses request. */
    fun of(input: JsonArray?, record: CodeModeRecord, candidateMedia: Map<String, List<JsonElement>>): CodeModeExtra {
        val (projected, boundary) = input?.let { onBaseline(it, record) } ?: return CodeModeExtra.STEERING
        val owned = (record.results.keys + record.pending.map(CodeModePending::clientId)).toSet()
        val logicalExtra = unownedItems(projected.logicalItems, record, owned, candidateMedia, boundary)
        return when {
            unexpectedReplay(projected, record, owned) || logicalExtra.any { !isSystemMessage(it) } ->
                CodeModeExtra.STEERING
            logicalExtra.isNotEmpty() -> CodeModeExtra.SYSTEM
            else -> CodeModeExtra.NONE
        }
    }

    private fun onBaseline(input: JsonArray, record: CodeModeRecord): Pair<ResponsesCodeModeInput, Int>? {
        val projected = codec.conversation(codec.projection.project(input)).body
        val boundary = codec.baselineBoundary(projected.logicalItems, record)
        val validBaseline = codec.validFullPrefix(input, record) || boundary != null
        return if (validBaseline) projected to (boundary ?: record.baselineLogicalCount) else null
    }

    private fun unownedItems(
        items: List<JsonElement>,
        record: CodeModeRecord,
        owned: Set<String>,
        candidateMedia: Map<String, List<JsonElement>>,
        tailStart: Int,
    ): List<JsonElement> {
        val ownedFollowUps = ownership.followUps(items, record, candidateMedia)
        val afterContinuity = if (codec.continuityAt(items, tailStart, record.continuity)) {
            tailStart + record.continuity.size
        } else {
            tailStart
        }
        return (afterContinuity until items.size).filter { index ->
            !ownership.isCallback(items[index], owned) && index !in ownedFollowUps
        }.map(items::get)
    }

    private fun unexpectedReplay(
        projected: ResponsesCodeModeInput,
        record: CodeModeRecord,
        owned: Set<String>,
    ): Boolean {
        val allowed = CodeModeNativeChain.allowed(record)
        return projected.replayItems.any { replay ->
            val slot = replay.logicalOffset to replay.items
            val expected = slot in allowed
            replay.logicalOffset >= record.baselineLogicalCount && !expected && replay.callbackId !in owned
        }
    }

    private fun isSystemMessage(element: JsonElement): Boolean {
        val item = element as? JsonObject
        val message = codec.string(item, CODE_MODE_FIELD_TYPE) in messageTypes
        return (message && codec.string(item, CODE_MODE_FIELD_ROLE) == ResponsesContextMessage.CLIENT_ROLE) ||
            ResponsesContextMessage.isContext(item)
    }
}
