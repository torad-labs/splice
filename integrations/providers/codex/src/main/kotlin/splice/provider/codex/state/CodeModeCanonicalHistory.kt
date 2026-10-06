// NEW: v5 history is indexed once and emitted once, with ownership independent of earlier rewrites.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CODE_MODE_FIELD_CALL_ID
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeCallReplay
import splice.provider.codex.CodeModeOmission
import splice.provider.codex.CodeModeOwnership
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.TYPE_CUSTOM_OUTPUT
import splice.provider.codex.state.native.CodeModeCapturedOrder

internal data class CodeModeCanonicalResult(
    val input: ResponsesCodeModeInput,
    val omitted: List<CodeModeOmission>,
)

internal enum class CodeModeCanonicalEmission { SCRIPT, CONTINUITY }

internal data class CodeModeCanonicalPlacement(
    val record: CodeModeRecord,
    val boundary: Int,
    val removed: Set<Int>,
    val retainedCallbacks: Set<String>,
    val emission: CodeModeCanonicalEmission = CodeModeCanonicalEmission.SCRIPT,
) {
    val logicalSize: Int
        get() = record.continuity.size + when (emission) {
            CodeModeCanonicalEmission.SCRIPT -> 2 + record.accepted.durableMedia().size
            CodeModeCanonicalEmission.CONTINUITY -> 0
        }
}

/** No I/O or registry reads: the supplied record view is the entire rewrite authority. */
internal class CodeModeCanonicalHistory(private val codec: CodexCodeModeHistoryCodec) {
    private val ownership = CodeModeOwnership(codec)
    private val continuity = CodeModeExtraContent(codec, ownership)

    fun rewrite(
        input: ResponsesCodeModeInput,
        records: List<CodeModeRecord>,
        media: Map<String, List<JsonElement>>,
        capture: CodeModeRecord? = null,
    ): CodeModeCanonicalResult {
        if (records.none { it.replayAnchors != null }) {
            val replay = CodeModeNativeChain.normalizedReplay(input.replayItems.filter { it.items.isNotEmpty() })
            val omitted = records.map {
                CodeModeOmission(it, "code-mode continuity anchor no longer places its owned callbacks")
            }
            return CodeModeCanonicalResult(input.copy(replayItems = replay), omitted)
        }
        val index = CodeModeHistoryIndex(input.logicalItems, codec)
        val natives = CodeModeNativeReplay(codec, input, index, records)
        val omitted = mutableListOf<CodeModeOmission>()
        val placements = placements(records, index, natives, media, omitted)
        val captured = capture?.takeIf { it.replayAnchors != null }?.let { CodeModeCapturedOrder(it, index, codec) }
        val order = captured?.takeIf { it.accepts(input) }
        order?.support(records, placements)
        val refused = captured?.takeUnless { it === order }
        return CodeModeCanonicalResult(emit(input, placements, natives, order, refused), omitted)
    }

    private fun placements(
        records: List<CodeModeRecord>,
        index: CodeModeHistoryIndex,
        natives: CodeModeNativeReplay,
        media: Map<String, List<JsonElement>>,
        omitted: MutableList<CodeModeOmission>,
    ): List<CodeModeCanonicalPlacement> = records.mapNotNull { record ->
        val boundary = index.boundary(record)
        val invalid = if (boundary == null) {
            "code-mode continuity anchor no longer places its owned callbacks"
        } else {
            metadataProblem(record) ?: replayProblem(index, record, media)
        }
        val error = invalid ?: natives.problem(record)
        if (error != null) omitted += CodeModeOmission(record, error, natives.rejection(record, error))
        when {
            invalid != null -> null
            error != null || natives.retainedResponse(record) ->
                continuityPlacement(index, record, checkNotNull(boundary))
            else -> placement(index, record, checkNotNull(boundary))
        }
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
                type == "custom_tool_call" && item !in setOf(record.outer, CodeModeCallReplay.item(record)) ->
                    "code-mode opaque call was edited"
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
        removed += index.continuityEcho(record)
        removed += continuity.replayIndexes(index.items, boundary, record)
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

    private fun continuityPlacement(
        index: CodeModeHistoryIndex,
        record: CodeModeRecord,
        boundary: Int,
    ): CodeModeCanonicalPlacement {
        val removed = index.owned(record).filter { ownership.isOpaque(index.items[it], record.outerCallId) }.toSet() +
            index.continuityEcho(record) + continuity.replayIndexes(index.items, boundary, record)
        return CodeModeCanonicalPlacement(
            record,
            boundary,
            removed,
            record.clientIds(),
            CodeModeCanonicalEmission.CONTINUITY,
        )
    }

    private fun lateCallbacks(index: CodeModeHistoryIndex, record: CodeModeRecord): Set<String> =
        index.owned(record).mapNotNull { at ->
            val item = index.items[at] as? JsonObject
            val id = codec.string(item, CODE_MODE_FIELD_CALL_ID)
            id.takeIf { codec.string(item, CODE_MODE_FIELD_TYPE) == "function_call_output" && id !in record.results }
        }.toSet()

    private fun append(
        placement: CodeModeCanonicalPlacement,
        logical: MutableList<JsonElement>,
        replay: MutableList<ResponsesCodeModeReplay>,
        starts: MutableMap<String, Int>,
        order: CodeModeCapturedOrder?,
    ) {
        val record = placement.record
        starts[record.id] = logical.size
        if (order == null) {
            record.continuityReplay.forEach {
                replay += ResponsesCodeModeReplay(logical.size + it.logicalOffset, null, it.items)
            }
        }
        logical += record.continuity
        when (placement.emission) {
            CodeModeCanonicalEmission.SCRIPT -> {
                logical += CodeModeCallReplay.item(record)
                logical += codec.customOutput(record)
                logical += record.accepted.durableMedia()
            }
            CodeModeCanonicalEmission.CONTINUITY -> Unit
        }
    }

    private fun capturedReplay(
        placements: List<CodeModeCanonicalPlacement>,
        offsets: IntArray,
        starts: Map<String, Int>,
        order: CodeModeCapturedOrder?,
    ): List<ResponsesCodeModeReplay> {
        val captured = order ?: return emptyList()
        val end = captured.prefixEnd(offsets) ?: offsets.last()
        return captured.native(offsets) + placements.flatMap { placement ->
            captured.continuity(placement.record, starts.getValue(placement.record.id), end)
        }
    }

    private fun emit(
        input: ResponsesCodeModeInput,
        placements: List<CodeModeCanonicalPlacement>,
        natives: CodeModeNativeReplay,
        order: CodeModeCapturedOrder?,
        refused: CodeModeCapturedOrder?,
    ): ResponsesCodeModeInput {
        val planned = order?.placements(placements) ?: placements
        val inserted = planned.groupBy(CodeModeCanonicalPlacement::boundary)
        val removed = placements.flatMap(CodeModeCanonicalPlacement::removed).toSet()
        val logical = mutableListOf<JsonElement>()
        val continuityReplay = mutableListOf<ResponsesCodeModeReplay>()
        val offsets = IntArray(input.logicalItems.size + 1)
        val starts = mutableMapOf<String, Int>()
        for (at in offsets.indices) {
            offsets[at] = logical.size
            val group = inserted[at].orEmpty()
            (order?.ordered(group) ?: group).forEach { placement ->
                append(placement, logical, continuityReplay, starts, order)
            }
            if (at < input.logicalItems.size && at !in removed) logical += input.logicalItems[at]
        }
        val replay = natives.rewrite(planned, offsets, starts, order, refused)
        continuityReplay += capturedReplay(planned, offsets, starts, order)
        continuityReplay += refused?.preserved(offsets).orEmpty()
        return ResponsesCodeModeInput(logical, CodeModeNativeChain.emittedReplay(replay + continuityReplay))
    }
}
