// NEW: v5 history is indexed once and emitted once, with ownership independent of earlier rewrites.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputItemDigests
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.provider.codex.CODE_MODE_FIELD_CALL_ID
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeCallReplay
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodeModeOmission
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.TYPE_CUSTOM_OUTPUT
import splice.provider.codex.history.CodeModeOwnership
import splice.provider.codex.state.native.CodeModeCapturedOrder
import splice.provider.codex.state.replay.CodeModeCanonicalEmitter

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
    val source: CodeModeContinuity = record.carry.source(),
) {
    val logicalSize: Int
        get() = source.logicalItems.size + source.afterExec.logicalItems.size + when (emission) {
            CodeModeCanonicalEmission.SCRIPT ->
                2 + record.accepted.durableMedia().size + record.progress.nativeNotifications.size
            CodeModeCanonicalEmission.CONTINUITY -> 0
        }
}

/** No I/O or registry reads: the supplied record view is the entire rewrite authority. */
internal class CodeModeCanonicalHistory(private val codec: CodexCodeModeHistoryCodec) {
    private val ownership = CodeModeOwnership(codec)
    private val continuity = CodeModeExtraContent(codec, ownership)
    private val emitter = CodeModeCanonicalEmitter(codec)

    /** [digests]: the request body's item digests, shared with the turn's later indexes over the same items. */
    fun rewrite(
        input: ResponsesCodeModeInput,
        records: List<CodeModeRecord>,
        media: Map<String, List<JsonElement>>,
        capture: CodeModeRecord? = null,
        digests: InputItemDigests = InputItemDigests(),
    ): CodeModeCanonicalResult {
        if (records.none { it.replayAnchors != null }) {
            val replay = CodeModeNativeChain.normalizedReplay(input.replayItems.filter { it.items.isNotEmpty() })
            val omitted = records.map {
                CodeModeOmission(it, "code-mode continuity anchor no longer places its owned callbacks")
            }
            return CodeModeCanonicalResult(input.copy(replayItems = replay), omitted)
        }
        val index = CodeModeHistoryIndex(input.logicalItems, codec, digests)
        val inputOwners = records.filterNot(CodeModeNativeChain::retired)
        val natives = CodeModeNativeReplay(codec, input, index, inputOwners)
        val omitted = mutableListOf<CodeModeOmission>()
        val placements = placements(records, index, natives, media, omitted).map(natives.sources::prepare)
        val captured = capture?.takeUnless(CodeModeNativeChain::retired)?.takeIf { it.replayAnchors != null }
            ?.let { CodeModeCapturedOrder(it, index, codec) }
        val order = captured?.takeIf { it.accepts(input) }
        order?.support(inputOwners, placements)
        val refused = captured?.takeUnless { it === order }
        return CodeModeCanonicalResult(emitter.emit(input, placements, natives, order, refused), omitted)
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
            error != null || responseOnly(record, natives) ->
                continuityPlacement(index, record, checkNotNull(boundary))
            else -> placement(index, record, checkNotNull(boundary))
        }
    }

    private fun responseOnly(record: CodeModeRecord, natives: CodeModeNativeReplay): Boolean =
        CodeModeNativeChain.retired(record) || natives.retainedResponse(record)

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
                    item !in record.progress.nativeNotifications -> "code-mode opaque output was edited"
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
            ownership.isCallback(index.items[at], ids) || ownership.isOpaque(index.items[at], record.origin.outerCallId)
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
        val outerCallId = record.origin.outerCallId
        val removed = index.owned(record).filter { ownership.isOpaque(index.items[it], outerCallId) }.toSet() +
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
}
