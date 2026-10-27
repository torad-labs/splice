// NEW: classify client content outside a parked script's owned callbacks and baseline.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputItemDigests
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.dialect.responses.request.ResponsesContextMessage
import splice.provider.codex.CODE_MODE_FIELD_ROLE
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeExtra
import splice.provider.codex.CodeModeIssuedStep
import splice.provider.codex.CodeModePending
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.CodeModeAcknowledgedBaseline
import splice.provider.codex.history.CodeModeDeliveredEchoes
import splice.provider.codex.history.CodeModeOwnership
import splice.provider.codex.history.CodeModeReasoningIdentity
import splice.provider.codex.history.CodeModeScriptPlacements
import splice.provider.codex.state.diagnostics.CodeModeHistoryLog
import splice.provider.codex.state.replay.CodeModeCallbackReplay
import splice.provider.codex.state.replay.CodeModeReplayEvidence

internal class CodeModeExtraContent(
    private val codec: CodexCodeModeHistoryCodec,
    private val ownership: CodeModeOwnership,
) {
    private val scripts = CodeModeScriptPlacements(codec)
    private val delivered = CodeModeDeliveredEchoes(codec, scripts)

    /** [input]: the round's parsed input array, or null when the round is not a Responses request.
     *  [digests]: the round's item digests, so this check's two baseline indexes render no item again. */
    fun of(
        input: JsonArray?,
        record: CodeModeRecord,
        candidateMedia: Map<String, List<JsonElement>>,
        digests: InputItemDigests = InputItemDigests(),
        clearedCallbacks: Set<String> = emptySet(),
    ): CodeModeExtra {
        val (projected, boundary) = input?.let {
            onBaseline(it, record, digests, clearedCallbacks)
        } ?: return CodeModeExtra.STEERING
        val owned = (record.results.keys + record.progress.pending.map(CodeModePending::clientId)).toSet()
        val logicalExtra = unownedIndexes(projected.logicalItems, record, owned, candidateMedia, boundary)
            .map(projected.logicalItems::get)
        val replayed = unexpectedReplay(projected, record, owned, boundary)
        return when {
            replayed || logicalExtra.any { !isSystemMessage(it) } ->
                CodeModeExtra.STEERING
            logicalExtra.isNotEmpty() -> CodeModeExtra.SYSTEM
            else -> CodeModeExtra.NONE
        }
    }

    /** Only whitelisted kinds and numeric positions leave the history through this diagnostic. */
    fun describe(
        input: JsonArray?,
        record: CodeModeRecord,
        candidateMedia: Map<String, List<JsonElement>>,
        extra: CodeModeExtra,
        proof: CodeModeExtraProof = CodeModeExtraProof(),
    ): String {
        val digests = proof.digests
        val baseline = input?.let { onBaseline(it, record, digests, proof.clearedCallbacks) }
        val tail = baseline?.let { (projected, boundary) ->
            val owned = (record.results.keys + record.progress.pending.map(CodeModePending::clientId)).toSet()
            val items = unownedIndexes(projected.logicalItems, record, owned, candidateMedia, boundary).map {
                "${CodeModeHistoryLog.extraItemKind(projected.logicalItems[it], codec)}@$it"
            }
            if (unexpectedReplay(projected, record, owned, boundary)) items + "unexpectedReplay" else items
        } ?: listOf("baselineMismatch")
        val answered = record.progress.pending.all { it.clientId in record.results }
        return "[code-mode] interrupted extra=$extra baselineLogicalCount=${record.origin.baseline.logicalCount} " +
            "boundary=${baseline?.second ?: "unresolved"} unowned=[${tail.joinToString(",")}] answered=$answered"
    }

    private fun onBaseline(
        input: JsonArray,
        record: CodeModeRecord,
        digests: InputItemDigests,
        clearedCallbacks: Set<String>,
    ): Pair<ResponsesCodeModeInput, Int>? {
        val projected = codec.conversation(codec.projection.project(input)).body
        val acknowledged = record.replayAnchors?.client?.acknowledgement
        if (acknowledged != null) {
            return CodeModeAcknowledgedBaseline.locate(projected, acknowledged, clearedCallbacks, scripts)
        }
        val boundary = codec.baselineBoundary(projected.logicalItems, record, digests)
        val validBaseline = codec.validFullPrefix(input, record) || boundary != null
        return if (validBaseline) projected to (boundary ?: record.origin.baseline.logicalCount) else null
    }

    private fun unownedIndexes(
        items: List<JsonElement>,
        record: CodeModeRecord,
        owned: Set<String>,
        candidateMedia: Map<String, List<JsonElement>>,
        tailStart: Int,
    ): List<Int> {
        val ownedFollowUps = ownership.followUps(items, record, candidateMedia)
        val delivered = indexes(items, tailStart, record, candidateMedia)
        return (tailStart until items.size).filter { index ->
            !ownership.isCallback(items[index], owned) && index !in ownedFollowUps && index !in delivered
        }
    }

    /** Matches only already delivered client prose; terminal model items remain unchanged for upstream replay. */
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

    /** A cut without terminal model continuity keeps its echoed prose in the original history. */
    fun replayIndexes(items: List<JsonElement>, boundary: Int, record: CodeModeRecord): Set<Int> =
        if (record.carry.continuity.isEmpty()) emptySet() else indexes(items, boundary, record)

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

    private fun skippable(
        item: JsonElement,
        index: Int,
        record: CodeModeRecord,
        owned: Set<String>,
        followUps: Set<Int>,
    ): Boolean {
        val callback = ownership.isCallback(item, owned) || ownership.isOpaque(item, record.origin.outerCallId)
        return callback || index in followUps || isSystemMessage(item)
    }

    private fun isSystemMessage(element: JsonElement): Boolean {
        val item = element as? JsonObject
        val message = codec.string(item, CODE_MODE_FIELD_TYPE) in setOf("", "message")
        return (message && codec.string(item, CODE_MODE_FIELD_ROLE) == ResponsesContextMessage.CLIENT_ROLE) ||
            ResponsesContextMessage.isContext(item)
    }

    /** Native envelopes are client echoes only when their exact step and callback still place them. */
    fun deliveredReplay(
        projected: ResponsesCodeModeInput,
        record: CodeModeRecord,
        digests: InputItemDigests = InputItemDigests(),
    ): Set<ResponsesCodeModeReplay> {
        val boundary = codec.baselineBoundary(projected.logicalItems, record, digests) ?: return emptySet()
        return deliveredReplay(projected, record, boundary)
    }

    /** Classification has already resolved the acknowledged client boundary, which can differ from the model's. */
    private fun deliveredReplay(
        projected: ResponsesCodeModeInput,
        record: CodeModeRecord,
        boundary: Int,
    ): Set<ResponsesCodeModeReplay> {
        var lower = boundary
        val matched = linkedSetOf<ResponsesCodeModeReplay>()
        val prose = indexes(projected.logicalItems, lower, record)
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
            skippable(projected.logicalItems[it], it, record, record.clientIds(), prose)
        }
        // GPT's reasoning by its identity, whichever copy holds it: the stream's, or the client's from its envelope.
        val replayed = candidates.flatMap { replay -> replay.items }.map(CodeModeReasoningIdentity::key)
        return candidates.takeIf { contextOnly && replayed == expected.map(CodeModeReasoningIdentity::key) }.orEmpty()
    }

    private fun unexpectedReplay(
        projected: ResponsesCodeModeInput,
        record: CodeModeRecord,
        owned: Set<String>,
        boundary: Int,
    ): Boolean {
        val evidence = CodeModeReplayEvidence(record, boundary)
        val natives = CodeModeCallbackReplay.native(projected, owned)
        val current = evidence.current(natives)
        val witnessed = deliveredReplay(projected, record, boundary) + deliveredReplay(natives, record, boundary) +
            deliveredReplay(current, record, boundary) + streamedContinuity(projected, record, boundary) +
            streamedContinuity(natives, record, boundary) + streamedContinuity(current, record, boundary)
        return projected.replayItems.any { replay ->
            val expected = evidence.owns(replay, witnessed) ||
                evidence.owns(evidence.current(CodeModeCallbackReplay.native(replay, owned)), witnessed)
            replay.logicalOffset >= boundary && !expected
        }
    }

    /** The client's copy of the reasoning GPT gave before the script's call, when GPT streamed some of it after the
     *  preface: the client keeps that order, so the copy is split across the slots from [boundary] to the script's
     *  first client call, each in GPT's order. Empty unless those slots hold exactly that reasoning. */
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

internal data class CodeModeExtraProof(
    val digests: InputItemDigests = InputItemDigests(),
    val clearedCallbacks: Set<String> = emptySet(),
)
