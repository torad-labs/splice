// NEW: classify client content outside a parked script's owned callbacks and baseline.
package splice.provider.codex.state

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import splice.core.perf.InputItemDigests
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodeModeExtra
import splice.provider.codex.CodeModePending
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.CodeModeAcknowledgedBaseline
import splice.provider.codex.history.CodeModeOwnership
import splice.provider.codex.history.CodeModeScriptPlacements
import splice.provider.codex.state.diagnostics.CodeModeHistoryLog
import splice.provider.codex.state.replay.CodeModeClientEchoes
import splice.provider.codex.state.replay.CodeModeExtraReplay

internal class CodeModeExtraContent(
    private val codec: CodexCodeModeHistoryCodec,
    private val ownership: CodeModeOwnership,
) {
    private val scripts = CodeModeScriptPlacements(codec)
    private val echoes = CodeModeClientEchoes(codec, ownership, scripts)
    private val replay = CodeModeExtraReplay(codec, echoes)

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
        val replayed = replay.unexpected(projected, record, owned, boundary)
        return when {
            replayed || logicalExtra.any { !echoes.isSystemMessage(it) } ->
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
            if (replay.unexpected(projected, record, owned, boundary)) items + "unexpectedReplay" else items
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
    ): Set<Int> = echoes.indexes(items, boundary, record, candidateMedia)

    /** A cut without terminal model continuity keeps its echoed prose in the original history. */
    fun replayIndexes(items: List<JsonElement>, boundary: Int, record: CodeModeRecord): Set<Int> =
        if (record.carry.continuity.isEmpty()) emptySet() else indexes(items, boundary, record)

    /** Native envelopes are client echoes only when their exact step and callback still place them. */
    fun deliveredReplay(
        projected: ResponsesCodeModeInput,
        record: CodeModeRecord,
        digests: InputItemDigests = InputItemDigests(),
    ): Set<ResponsesCodeModeReplay> = replay.delivered(projected, record, digests)
}

internal data class CodeModeExtraProof(
    val digests: InputItemDigests = InputItemDigests(),
    val clearedCallbacks: Set<String> = emptySet(),
)
