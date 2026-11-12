package splice.provider.codex.state.replay

import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputItemDigests
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeIssuedStep
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.CodeModeReasoningIdentity

/** Certifies client replay against the exact delivered script and streamed reasoning. */
internal class CodeModeExtraReplay(
    private val codec: CodexCodeModeHistoryCodec,
    private val echoes: CodeModeClientEchoes,
) {
    fun delivered(
        projected: ResponsesCodeModeInput,
        record: CodeModeRecord,
        digests: InputItemDigests,
    ): Set<ResponsesCodeModeReplay> {
        val boundary = codec.baselineBoundary(projected.logicalItems, record, digests) ?: return emptySet()
        return delivered(projected, record, boundary)
    }

    fun unexpected(
        projected: ResponsesCodeModeInput,
        record: CodeModeRecord,
        owned: Set<String>,
        boundary: Int,
    ): Boolean {
        val evidence = CodeModeReplayEvidence(record, boundary)
        val natives = CodeModeCallbackReplay.native(projected, owned)
        val current = evidence.current(natives)
        val witnessed = delivered(projected, record, boundary) + delivered(natives, record, boundary) +
            delivered(current, record, boundary) + streamedContinuity(projected, record, boundary) +
            streamedContinuity(natives, record, boundary) + streamedContinuity(current, record, boundary)
        return projected.replayItems.any { replay ->
            val expected = evidence.owns(replay, witnessed) ||
                evidence.owns(evidence.current(CodeModeCallbackReplay.native(replay, owned)), witnessed)
            replay.logicalOffset >= boundary && !expected
        }
    }

    /** An acknowledged client boundary can differ from the model's. */
    private fun delivered(
        projected: ResponsesCodeModeInput,
        record: CodeModeRecord,
        boundary: Int,
    ): Set<ResponsesCodeModeReplay> {
        var lower = boundary
        val matched = linkedSetOf<ResponsesCodeModeReplay>()
        val prose = echoes.indexes(projected.logicalItems, lower, record)
        for (step in record.issued) {
            val callback = step.calls.firstOrNull()?.clientId?.let { id ->
                projected.logicalItems.indexOfFirst {
                    codec.callId(it) == id && codec.string(it as? JsonObject, CODE_MODE_FIELD_TYPE) == "function_call"
                }
            } ?: -1
            if (callback < lower) continue
            matched += deliveredCandidates(projected, record, step, lower..callback, prose)
            lower = callback + 1
        }
        return matched
    }

    private fun deliveredCandidates(
        projected: ResponsesCodeModeInput,
        record: CodeModeRecord,
        step: CodeModeIssuedStep,
        range: IntRange,
        prose: Set<Int>,
    ): List<ResponsesCodeModeReplay> {
        val expected = step.deliveredNative?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val candidates = projected.replayItems.filter { it.logicalOffset in range }
        val contextOnly = (range.first until range.last).all {
            echoes.skippable(projected.logicalItems[it], it, record, record.clientIds(), prose)
        }
        val replayed = candidates.flatMap { replay -> replay.items }.map(CodeModeReasoningIdentity::key)
        return candidates.takeIf { contextOnly && replayed == expected.map(CodeModeReasoningIdentity::key) }.orEmpty()
    }

    /** The client must preserve the complete ordered reasoning delivered before the script's first callback. */
    private fun streamedContinuity(
        projected: ResponsesCodeModeInput,
        record: CodeModeRecord,
        boundary: Int,
    ): Set<ResponsesCodeModeReplay> {
        val given = record.carry.replay.flatMap { it.items }
        if (given.isEmpty()) return emptySet()
        val call = record.issued.firstOrNull()?.calls?.firstOrNull()?.clientId
        val callback = call?.let { codec.projection.functionCallIndex(projected.logicalItems, it) }
            ?.takeIf { it >= boundary } ?: projected.logicalItems.size
        val copy = mutableListOf<ResponsesCodeModeReplay>()
        var held = 0
        for (replay in projected.replayItems.filter { it.logicalOffset in boundary..callback }) {
            if (held >= given.size) break
            copy += replay
            held += replay.items.size
        }
        val copied = copy.flatMap(ResponsesCodeModeReplay::items).map(CodeModeReasoningIdentity::key)
        return copy.toSet().takeIf { copied == given.map(CodeModeReasoningIdentity::key) }.orEmpty()
    }
}
