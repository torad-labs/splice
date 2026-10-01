// NEW: v5 history is indexed once and emitted once, with ownership independent of earlier rewrites.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CODE_MODE_FIELD_CALL_ID
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeOmission
import splice.provider.codex.CodeModeOwnership
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.TYPE_CUSTOM_OUTPUT

internal data class CodeModeCanonicalResult(
    val input: ResponsesCodeModeInput,
    val omitted: List<CodeModeOmission>,
)

internal data class CodeModeCanonicalPlacement(
    val record: CodeModeRecord,
    val boundary: Int,
    val removed: Set<Int>,
    val retainedCallbacks: Set<String>,
)

/** No I/O or registry reads: the supplied record view is the entire rewrite authority. */
internal class CodeModeCanonicalHistory(private val codec: CodexCodeModeHistoryCodec) {
    private val ownership = CodeModeOwnership(codec)

    fun rewrite(
        input: ResponsesCodeModeInput,
        records: List<CodeModeRecord>,
        media: Map<String, List<JsonElement>>,
    ): CodeModeCanonicalResult {
        val index = CodeModeHistoryIndex(input.logicalItems, codec)
        val natives = CodeModeNativeReplay(codec, input, index, records)
        val omitted = mutableListOf<CodeModeOmission>()
        val placements = records.mapNotNull { record ->
            val boundary = index.boundary(record)
            val error = if (boundary == null) {
                "code-mode continuity anchor no longer places its owned callbacks"
            } else {
                metadataProblem(record) ?: replayProblem(index, record, media) ?: natives.problem(record)
            }
            if (error != null) {
                omitted += CodeModeOmission(record, error)
                null
            } else {
                placement(index, record, checkNotNull(boundary))
            }
        }
        return CodeModeCanonicalResult(emit(input, placements, natives), omitted)
    }

    private fun metadataProblem(record: CodeModeRecord): String? = when {
        record.nativeSegments.any { it.logicalOffset !in 0..record.baselineLogicalCount } ->
            "code-mode replay metadata has an invalid native offset"
        record.continuityReplay.any { it.logicalOffset !in 0..record.continuity.size } ->
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
                type == "custom_tool_call" && item != record.outer -> "code-mode opaque call was edited"
                type == TYPE_CUSTOM_OUTPUT && item != codec.customOutput(record) -> "code-mode opaque output was edited"
                else -> null
            }
        }

    private fun placement(
        index: CodeModeHistoryIndex,
        record: CodeModeRecord,
        boundary: Int,
    ): CodeModeCanonicalPlacement {
        val late = lateCallbacks(index, record)
        val ids = record.clientIds() - late
        val removed = index.owned(record).filter { at ->
            ownership.isCallback(index.items[at], ids) || ownership.isOpaque(index.items[at], record.outerCallId)
        }.toMutableSet()
        if (codec.continuityAt(index.items, boundary, record.continuity)) {
            removed += boundary until boundary + record.continuity.size
        }
        index.owned(record).forEach { at ->
            val item = index.items[at] as? JsonObject
            val media = when (codec.string(item, CODE_MODE_FIELD_TYPE)) {
                "function_call_output" -> record.accepted.media(codec.string(item, CODE_MODE_FIELD_CALL_ID)).orEmpty()
                TYPE_CUSTOM_OUTPUT -> record.accepted.durableMedia()
                else -> emptyList()
            }
            val end = at + 1 + media.size
            if (end <= index.items.size && index.items.subList(at + 1, end) == media) {
                removed += at + 1 until end
            }
        }
        return CodeModeCanonicalPlacement(record, boundary, removed, late)
    }

    private fun lateCallbacks(index: CodeModeHistoryIndex, record: CodeModeRecord): Set<String> =
        index.owned(record).mapNotNull { at ->
            val item = index.items[at] as? JsonObject
            val id = codec.string(item, CODE_MODE_FIELD_CALL_ID)
            id.takeIf { codec.string(item, CODE_MODE_FIELD_TYPE) == "function_call_output" && id !in record.results }
        }.toSet()

    private fun emit(
        input: ResponsesCodeModeInput,
        placements: List<CodeModeCanonicalPlacement>,
        natives: CodeModeNativeReplay,
    ): ResponsesCodeModeInput {
        val inserted = placements.groupBy(CodeModeCanonicalPlacement::boundary)
        val removed = placements.flatMap(CodeModeCanonicalPlacement::removed).toSet()
        val logical = mutableListOf<JsonElement>()
        val continuityReplay = mutableListOf<ResponsesCodeModeReplay>()
        val offsets = IntArray(input.logicalItems.size + 1)
        val starts = mutableMapOf<String, Int>()
        for (at in offsets.indices) {
            offsets[at] = logical.size
            inserted[at].orEmpty().forEach { placement ->
                val record = placement.record
                starts[record.id] = logical.size
                record.continuityReplay.forEach {
                    continuityReplay += ResponsesCodeModeReplay(logical.size + it.logicalOffset, null, it.items)
                }
                logical += record.continuity
                logical += record.outer
                logical += codec.customOutput(record)
                logical += record.accepted.durableMedia()
            }
            if (at < input.logicalItems.size && at !in removed) logical += input.logicalItems[at]
        }
        val replay = natives.rewrite(placements, offsets, starts)
        return ResponsesCodeModeInput(logical, CodeModeNativeChain.normalizedReplay(replay + continuityReplay))
    }
}
