package splice.provider.codex.state.replay

import kotlinx.serialization.json.JsonElement
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodeModeCallReplay
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.CodeModeScriptOutputs
import splice.provider.codex.state.CodeModeCanonicalEmission
import splice.provider.codex.state.CodeModeCanonicalPlacement
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeNativeReplay
import splice.provider.codex.state.native.CodeModeCapturedOrder

/** Emits an already validated placement plan in one pass, remapping every counted native occurrence with it. */
internal class CodeModeCanonicalEmitter(private val codec: CodexCodeModeHistoryCodec) {
    fun emit(
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
        return ResponsesCodeModeInput(
            logical,
            CodeModeNativeChain.emittedReplay(replay + continuityReplay, natives.payloads),
        )
    }

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
            placement.source.replayItems.forEach {
                replay += ResponsesCodeModeReplay(logical.size + it.logicalOffset, null, it.items)
            }
        }
        logical += placement.source.logicalItems
        if (placement.emission == CodeModeCanonicalEmission.SCRIPT) logical += CodeModeCallReplay.item(record)
        appendAfter(placement.source, logical, replay, order)
        when (placement.emission) {
            CodeModeCanonicalEmission.SCRIPT -> {
                logical += CodeModeScriptOutputs.items(record, codec)
            }
            CodeModeCanonicalEmission.CONTINUITY -> Unit
        }
    }

    private fun appendAfter(
        source: CodeModeContinuity,
        logical: MutableList<JsonElement>,
        replay: MutableList<ResponsesCodeModeReplay>,
        order: CodeModeCapturedOrder?,
    ) {
        source.afterExec.replayItems.forEach { segment ->
            val items = order?.claim(segment.items) ?: segment.items
            if (items.isNotEmpty()) {
                replay += segment.copy(
                    logicalOffset = logical.size + segment.logicalOffset,
                    items = items,
                )
            }
        }
        logical += source.afterExec.logicalItems
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
            captured.continuity(placement.record, starts.getValue(placement.record.id), end, placement.source)
        }
    }
}
