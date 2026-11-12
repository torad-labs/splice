// NEW: canonicalizes completed code-mode records at their persisted logical history boundaries.
package splice.provider.codex.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import splice.core.perf.InputItemDigests
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CODE_MODE_METADATA_VERSION
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeCallReplay
import splice.provider.codex.CodeModeExtra
import splice.provider.codex.CodeModeInputBoundary
import splice.provider.codex.CodeModePending
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRewrite
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.validation.CodeModeReplayValidation
import splice.provider.codex.state.CodeModeExtraContent
import splice.provider.codex.state.CodeModeExtraProof
import splice.provider.codex.state.CodeModeHistoryIndex
import splice.provider.codex.state.CodeModeMetadataValidator
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeNativeReplay
import splice.provider.codex.state.native.CodeModeAnchorCapture
import splice.provider.codex.state.native.CodeModeCanonicalRequests
import splice.provider.codex.state.native.CodeModeLegacyCanonicalization
import splice.upstream.RoundBody
import splice.provider.codex.state.diagnostics.CodeModeProjectedRewrite as ProjectedRewrite
internal class CodexCodeModeHistory(private val json: Json) {
    private val codec = CodexCodeModeHistoryCodec(json)
    private val canonicalizer = CodeModeCanonicalRequests(codec)
    private val metadata = CodeModeMetadataValidator()
    private val ownership = CodeModeOwnership(codec)
    private val nativeReplayValidator = CodeModeReplayValidation(codec, ownership)
    private val extras = CodeModeExtraContent(codec, ownership)

    fun anchoredBoundary(body: CodeModeBody, completed: List<CodeModeRecord>): CodeModeInputBoundary? =
        CodeModeAnchorCapture.inputBoundary(body.request?.second, completed, codec, body.digests)

    /** What the client added after the record's baseline that the record does not own (V4-336),
     *  sorted by [CodeModeExtraContent]. [candidateMedia]: follow-ups rendered for results the record
     *  has not accepted yet (this turn's), owned on sight so a screenshot arriving for a parked script
     *  is not "extra content". */
    fun extraContent(
        body: CodeModeBody,
        record: CodeModeRecord,
        candidateMedia: Map<String, List<JsonElement>> = emptyMap(),
        clearedCallbacks: Set<String> = emptySet(),
    ): CodeModeExtra = extras.of(body.request?.second, record, candidateMedia, body.digests, clearedCallbacks)

    fun describeExtraContent(
        body: CodeModeBody,
        record: CodeModeRecord,
        candidateMedia: Map<String, List<JsonElement>>,
        extra: CodeModeExtra,
        clearedCallbacks: Set<String> = emptySet(),
    ): String = extras.describe(
        body.request?.second,
        record,
        candidateMedia,
        extra,
        CodeModeExtraProof(body.digests, clearedCallbacks),
    )

    /**
     * Rewrites every completed record it can still place, except an abandoned one (V4-342): its
     * conversation went on upstream on the client's history, and a later rewrite of it would move the
     * baseline of any script started in that request. A record whose baseline no longer matches
     * is OMITTED, never fatal: its client calls stay in the history as the ordinary tool calls the
     * client already saw, which is the pre-code-mode wire shape. Records after an omitted one were
     * measured on top of its canonical items, so they omit too; the caller logs each omission.
     *
     * [replayMedia]: this turn's follow-ups per result id (V4-179). A CAPTURED result the client now
     * replays with different media is not the result the record accepted, and its record omits the
     * same way — the client's callback and its media stay as ordinary history — rather than the
     * canonical media riding beside the replayed ones. A legacy id (nothing captured) is not judged.
     */
    fun canonicalize(
        body: CodeModeBody,
        records: List<CodeModeRecord>,
        replayMedia: Map<String, List<JsonElement>> = emptyMap(),
        capture: CodeModeRecord? = null,
    ): CodeModeRewrite = canonicalizer.rewrite(
        body,
        records,
        replayMedia,
        capture,
        CodeModeLegacyCanonicalization(::canonicalizeRecord),
    )

    /** [canonicalize] for a request held as text: parsed once, as a text round is. */
    fun canonicalize(
        bodyJson: String,
        records: List<CodeModeRecord>,
        replayMedia: Map<String, List<JsonElement>> = emptyMap(),
    ): CodeModeRewrite = canonicalize(CodeModeBody(RoundBody.Text(bodyJson), json), records, replayMedia)

    fun restoreBaseline(body: CodeModeBody, record: CodeModeRecord): CodeModeRewrite {
        val root = body.request
            ?: return CodeModeRewrite(null, "code mode requires a Responses input array")
        val conversation = codec.conversation(codec.projection.project(root.second))
        val restored = restoreProjected(conversation.body, record, body.digests)
        return restored.error?.let { CodeModeRewrite(null, it, nativeRejection = restored.nativeRejection) }
            ?: codec.rebuilt(root.first, conversation, checkNotNull(restored.input), body)
    }

    private fun restoreProjected(
        input: ResponsesCodeModeInput,
        record: CodeModeRecord,
        digests: InputItemDigests,
    ): ProjectedRewrite {
        metadata.problem(record)?.let { return ProjectedRewrite(null, it) }
        val index = if (record.origin.baseline.metadataVersion == CODE_MODE_METADATA_VERSION) {
            CodeModeHistoryIndex(input.logicalItems, codec, digests)
        } else {
            null
        }
        if (!codec.validPrefix(input.logicalItems, record, index)) {
            return ProjectedRewrite(null, "code-mode logical history does not match its persisted baseline")
        }
        return if (record.origin.baseline.metadataVersion == CODE_MODE_METADATA_VERSION) {
            val native = CodeModeNativeReplay(
                codec,
                input,
                checkNotNull(index),
                listOf(record),
            )
            native.problem(record)?.let { ProjectedRewrite(null, it, native.rejection(record, it)) }
                ?: ProjectedRewrite(native.restore(record))
        } else {
            val replay = mergeNative(input.replayItems, record)
            replay.error?.let { ProjectedRewrite(null, it) }
                ?: ProjectedRewrite(ResponsesCodeModeInput(input.logicalItems, checkNotNull(replay.items)))
        }
    }

    private fun canonicalizeRecord(
        input: ResponsesCodeModeInput,
        record: CodeModeRecord,
        replayMedia: Map<String, List<JsonElement>>,
    ): ProjectedRewrite {
        val problem = metadata.problem(record)
            ?: "code-mode logical history does not match its persisted baseline".takeUnless {
                codec.validPrefix(input.logicalItems, record)
            }
            ?: nativeReplayValidator.mediaProblem(record, replayMedia)
        return problem?.let { ProjectedRewrite(null, it) } ?: rewriteRecord(input, record)
    }

    private fun rewriteRecord(input: ResponsesCodeModeInput, record: CodeModeRecord): ProjectedRewrite {
        val boundary = record.origin.baseline.logicalCount
        val continuity = extras.replayIndexes(input.logicalItems, boundary, record)
        val retained = ownership.retained(input.logicalItems, record, continuity)
            ?: return ProjectedRewrite(null, "code-mode owned history appears before its persisted boundary")
        nativeReplayValidator.opaqueProblem(input.logicalItems.drop(boundary), record)?.let {
            return ProjectedRewrite(null, it)
        }
        // V4-179: the record's follow-ups (each accepted result's images, in acceptance order) ride
        // ONCE, right after the canonical custom output — the same place the ordinary path puts a
        // tool_result's images, after its function_call_output — whether this is the live
        // continuation or a later turn's replay of the same record.
        val canonical = record.carry.continuity + CodeModeCallReplay.item(
            record,
        ) + CodeModeScriptOutputs.items(record, codec)
        val logical = input.logicalItems.filterIndexed { index, _ -> index in retained }.toMutableList()
        logical.addAll(boundary, canonical)
        val continuityReplay = record.carry.replay.map {
            ResponsesCodeModeReplay(boundary + it.logicalOffset, null, it.items)
        }
        val remapped = remapReplay(input.replayItems, record, retained, canonical.size)
        // V4-342: the natives first, then the continuity at the same offset: upstream's order, the search
        // the model made before its script and then the reasoning right before the script's call. Merged
        // after them, a native the client never replays (a tool search) landed behind the continuity.
        val replay = mergeNative(remapped, record)
        return replay.error?.let { ProjectedRewrite(null, it) }
            ?: ProjectedRewrite(ResponsesCodeModeInput(logical, placed(checkNotNull(replay.items), continuityReplay)))
    }

    private fun remapReplay(
        replay: List<ResponsesCodeModeReplay>,
        record: CodeModeRecord,
        retained: Set<Int>,
        inserted: Int,
    ): List<ResponsesCodeModeReplay> {
        val owned = (record.results.keys + record.progress.pending.map(CodeModePending::clientId)).toSet()
        val baselineNative = CodeModeNativeChain.replay(record).map { it.logicalOffset to it.items }.toSet()
        val continuityReplay = record.carry.replay.map {
            record.origin.baseline.logicalCount + it.logicalOffset to it.items
        }.toSet()
        return CodeModeNativeChain.withoutContinuity(replay, record).mapNotNull { segment ->
            val slot = segment.logicalOffset to segment.items
            when {
                segment.callbackId in owned -> null
                slot in continuityReplay -> null
                slot in baselineNative -> segment
                else -> {
                    val prior = (0 until segment.logicalOffset).count { it in retained }
                    val shift = if (segment.logicalOffset >= record.origin.baseline.logicalCount) inserted else 0
                    segment.copy(logicalOffset = prior + shift)
                }
            }
        }
    }

    private fun placed(
        natives: List<ResponsesCodeModeReplay>,
        continuity: List<ResponsesCodeModeReplay>,
    ): List<ResponsesCodeModeReplay> = CodeModeNativeChain.normalizedReplay(natives + continuity)

    private fun mergeNative(replay: List<ResponsesCodeModeReplay>, record: CodeModeRecord): ReplayRewrite {
        val expected = CodeModeNativeChain.replay(record).map { it.logicalOffset to it.items }
        val allowed = CodeModeNativeChain.allowed(record)
        val nativeIds = expected.flatMap { (_, items) -> items.mapNotNull(codec::callId) }.toSet()
        val expectedOffsets = expected.map(Pair<Int, List<JsonElement>>::first).toSet()
        val normalized = CodeModeNativeChain.normalizedReplay(replay)
        val conflict = normalized.any { segment ->
            val sameId = segment.items.any { codec.callId(it) in nativeIds }
            nativeReplayValidator.conflictsWithBaseline(
                segment,
                record.origin.baseline.logicalCount,
                allowed,
                expectedOffsets,
                sameId,
            )
        }
        if (conflict) return ReplayRewrite(null, "code-mode native replay conflicts with its captured baseline")
        val merged = normalized.toMutableList()
        expected.forEach { (offset, items) ->
            val present = merged.any {
                val sameSlot = it.logicalOffset == offset && it.callbackId == null
                sameSlot && CodeModeNativeChain.startsWith(it.items, items)
            }
            if (!present) merged += ResponsesCodeModeReplay(offset, null, items)
        }
        return ReplayRewrite(merged.sortedBy(ResponsesCodeModeReplay::logicalOffset))
    }
}

private data class ReplayRewrite(val items: List<ResponsesCodeModeReplay>?, val error: String? = null)
