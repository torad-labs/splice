package splice.provider.codex.state.replay

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeCallReplay
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.TYPE_CUSTOM_OUTPUT
import splice.provider.codex.state.CodeModeHistoryIndex

/** Validates captured callback identity and payloads before canonical history selects placements. */
internal class CodeModeCanonicalValidation(private val codec: CodexCodeModeHistoryCodec) {
    fun problem(
        index: CodeModeHistoryIndex,
        record: CodeModeRecord,
        media: Map<String, List<JsonElement>>,
    ): String? = metadataProblem(record) ?: replayProblem(index, record, media)

    private fun metadataProblem(record: CodeModeRecord): String? = when {
        record.carry.segments.any { it.logicalOffset !in 0..record.origin.baseline.logicalCount } ->
            "code-mode replay metadata has an invalid native offset"
        record.carry.replay.any { it.logicalOffset !in 0..record.carry.continuity.size } ->
            "code-mode replay metadata has an invalid continuity offset"
        else -> null
    }

    private fun replayProblem(
        index: CodeModeHistoryIndex,
        record: CodeModeRecord,
        media: Map<String, List<JsonElement>>,
    ): String? = when {
        record.clientIds().isNotEmpty() && index.owned(record).isEmpty() -> "code-mode owned callback history is absent"
        media.any { (id, value) -> record.accepted.media(id)?.let { it != value } == true } ->
            "code-mode result is replayed with media that differ from what its record captured"
        else -> callbackProblem(index, record) ?: opaqueProblem(index, record)
    }

    private fun callbackProblem(index: CodeModeHistoryIndex, record: CodeModeRecord): String? {
        val calls = index.owned(record).mapNotNull { index.items[it] as? JsonObject }
        val duplicate = calls.groupBy { codec.callId(it) to codec.string(it, CODE_MODE_FIELD_TYPE) }
            .values.any { it.size > 1 }
        val issued = record.issued.flatMap { it.calls }.associateBy { it.clientId }
        val edited = calls.any { item ->
            val expected = issued[codec.callId(item)]
            codec.string(item, CODE_MODE_FIELD_TYPE) == "function_call" &&
                expected != null && codec.string(item, "name") != expected.name
        }
        return "code-mode callback identity is ambiguous or was edited".takeIf { duplicate || edited }
    }

    private fun opaqueProblem(index: CodeModeHistoryIndex, record: CodeModeRecord): String? =
        index.owned(record).firstNotNullOfOrNull { at ->
            val item = index.items[at] as? JsonObject
            val type = codec.string(item, CODE_MODE_FIELD_TYPE)
            when {
                type == "custom_tool_call" && item?.let(index.payloads::token) !in setOf(
                    index.payloads.token(record.origin.outer),
                    index.payloads.token(CodeModeCallReplay.item(record)),
                ) ->
                    "code-mode opaque call was edited"
                type == TYPE_CUSTOM_OUTPUT && item != codec.customOutput(record) &&
                    record.progress.nativeNotifications.none { it == item } -> "code-mode opaque output was edited"
                else -> null
            }
        }
}
