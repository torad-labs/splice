// NEW: canonicalizes completed code-mode records at their persisted logical history boundaries.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.dialect.responses.request.ResponsesCodeModeReplay

internal class CodexCodeModeHistory(json: Json) {
    private val codec = CodexCodeModeHistoryCodec(json)
    private val nativeReplayValidator = NativeReplayValidator()
    private val ownership = CodeModeOwnership(codec)

    fun inputBoundary(bodyJson: String): CodeModeInputBoundary? = codec.inputBoundary(bodyJson)

    /** [candidateMedia]: follow-ups rendered for results the record has not accepted yet (this
     *  turn's), owned on sight so a screenshot arriving for a parked script is not "extra content". */
    fun hasExtraContent(
        bodyJson: String,
        record: CodeModeRecord,
        candidateMedia: Map<String, List<JsonElement>> = emptyMap(),
    ): Boolean {
        val input = codec.root(bodyJson)?.second ?: return true
        val projected = codec.conversation(codec.projection.project(input)).body
        val validBaseline = codec.validFullPrefix(input, record) ||
            codec.validPrefix(projected.logicalItems, record)
        if (!validBaseline) return true
        val owned = (record.results.keys + record.pending.map(CodeModePending::clientId)).toSet()
        val items = projected.logicalItems
        val ownedFollowUps = ownership.followUps(items, record, candidateMedia)
        val tailStart = record.baselineLogicalCount
        val continuityEnd = tailStart + record.continuity.size
        val afterContinuity = if (items.subList(tailStart, minOf(continuityEnd, items.size)) == record.continuity) {
            continuityEnd
        } else {
            tailStart
        }
        val logicalExtra = (afterContinuity until items.size).any { index ->
            !ownership.isCallback(items[index], owned) && index !in ownedFollowUps
        }
        val baselineReplay = record.nativeSegments.map { it.logicalOffset to it.items }.toSet()
        val continuityReplay = record.continuityReplay.map {
            record.baselineLogicalCount + it.logicalOffset to it.items
        }.toSet()
        val replayExtra = projected.replayItems.any { replay ->
            val slot = replay.logicalOffset to replay.items
            val expected = slot in baselineReplay || slot in continuityReplay
            !expected && replay.callbackId !in owned
        }
        return logicalExtra || replayExtra
    }

    /**
     * Rewrites every completed record it can still place. A record whose baseline no longer matches
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
        bodyJson: String,
        records: List<CodeModeRecord>,
        replayMedia: Map<String, List<JsonElement>> = emptyMap(),
    ): CodeModeRewrite {
        val root = codec.root(bodyJson)
            ?: return CodeModeRewrite(null, "code mode requires a Responses input array")
        val conversation = codec.conversation(codec.projection.project(root.second))
        var body = conversation.body
        val omitted = mutableListOf<CodeModeOmission>()
        records.forEach { record ->
            val rewritten = canonicalizeRecord(body, record, replayMedia)
            val error = rewritten.error
            if (error == null) body = checkNotNull(rewritten.input) else omitted += CodeModeOmission(record, error)
        }
        return codec.rebuilt(root.first, conversation, body).copy(omitted = omitted)
    }

    fun restoreBaseline(bodyJson: String, record: CodeModeRecord): CodeModeRewrite {
        val root = codec.root(bodyJson)
            ?: return CodeModeRewrite(null, "code mode requires a Responses input array")
        val conversation = codec.conversation(codec.projection.project(root.second))
        val restored = restoreProjected(conversation.body, record)
        return restored.error?.let { CodeModeRewrite(null, it) }
            ?: codec.rebuilt(root.first, conversation, checkNotNull(restored.input))
    }

    private fun restoreProjected(input: ResponsesCodeModeInput, record: CodeModeRecord): ProjectedRewrite {
        metadataProblem(record)?.let { return ProjectedRewrite(null, it) }
        if (!codec.validPrefix(input.logicalItems, record)) {
            return ProjectedRewrite(null, "code-mode logical history does not match its persisted baseline")
        }
        val replay = mergeNative(input.replayItems, record)
        return replay.error?.let { ProjectedRewrite(null, it) }
            ?: ProjectedRewrite(ResponsesCodeModeInput(input.logicalItems, checkNotNull(replay.items)))
    }

    private fun canonicalizeRecord(
        input: ResponsesCodeModeInput,
        record: CodeModeRecord,
        replayMedia: Map<String, List<JsonElement>>,
    ): ProjectedRewrite {
        val problem = metadataProblem(record)
            ?: "code-mode logical history does not match its persisted baseline".takeUnless {
                codec.validPrefix(input.logicalItems, record)
            }
            ?: replayedMediaProblem(record, replayMedia)
        return problem?.let { ProjectedRewrite(null, it) } ?: rewriteRecord(input, record)
    }

    private fun replayedMediaProblem(record: CodeModeRecord, replayMedia: Map<String, List<JsonElement>>): String? {
        val changed = replayMedia.keys.firstOrNull { id ->
            record.accepted.media(id)?.let { captured -> captured != replayMedia.getValue(id) } == true
        } ?: return null
        return "code-mode result '$changed' is replayed with media that differ from what its record captured"
    }

    private fun rewriteRecord(input: ResponsesCodeModeInput, record: CodeModeRecord): ProjectedRewrite {
        val boundary = record.baselineLogicalCount
        val continuity = continuityIndexes(input.logicalItems, boundary, record.continuity)
        val retained = ownership.retained(input.logicalItems, record, continuity)
            ?: return ProjectedRewrite(null, "code-mode owned history appears before its persisted boundary")
        opaqueProblem(input.logicalItems.drop(boundary), record)?.let { return ProjectedRewrite(null, it) }
        // V4-179: the record's follow-ups (each accepted result's images, in acceptance order) ride
        // ONCE, right after the canonical custom output — the same place the ordinary path puts a
        // tool_result's images, after its function_call_output — whether this is the live
        // continuation or a later turn's replay of the same record.
        val canonical = record.continuity + record.outer + codec.customOutput(record) + record.accepted.durableMedia()
        val logical = input.logicalItems.filterIndexed { index, _ -> index in retained }.toMutableList()
        logical.addAll(boundary, canonical)
        val continuityReplay = record.continuityReplay.map {
            ResponsesCodeModeReplay(boundary + it.logicalOffset, null, it.items)
        }
        val remapped = remapReplay(input.replayItems, record, retained, canonical.size)
        val replay = mergeNative(remapped + continuityReplay, record)
        return replay.error?.let { ProjectedRewrite(null, it) }
            ?: ProjectedRewrite(ResponsesCodeModeInput(logical, checkNotNull(replay.items)))
    }

    private fun remapReplay(
        replay: List<ResponsesCodeModeReplay>,
        record: CodeModeRecord,
        retained: Set<Int>,
        inserted: Int,
    ): List<ResponsesCodeModeReplay> {
        val owned = (record.results.keys + record.pending.map(CodeModePending::clientId)).toSet()
        val baselineNative = record.nativeSegments.map { it.logicalOffset to it.items }.toSet()
        val continuityReplay = record.continuityReplay.map {
            record.baselineLogicalCount + it.logicalOffset to it.items
        }.toSet()
        return replay.mapNotNull { segment ->
            val slot = segment.logicalOffset to segment.items
            when {
                segment.callbackId in owned -> null
                slot in continuityReplay -> null
                slot in baselineNative -> segment
                else -> {
                    val prior = (0 until segment.logicalOffset).count { it in retained }
                    val shift = if (segment.logicalOffset >= record.baselineLogicalCount) inserted else 0
                    segment.copy(logicalOffset = prior + shift)
                }
            }
        }
    }

    private fun mergeNative(replay: List<ResponsesCodeModeReplay>, record: CodeModeRecord): ReplayRewrite {
        val expected = record.nativeSegments.map { it.logicalOffset to it.items }
        val continuity = record.continuityReplay.map {
            record.baselineLogicalCount + it.logicalOffset to it.items
        }
        val allowed = (expected + continuity).toSet()
        val nativeIds = expected.flatMap { (_, items) -> items.mapNotNull(codec::callId) }.toSet()
        val expectedOffsets = expected.map(Pair<Int, List<JsonElement>>::first).toSet()
        val conflict = replay.any { segment ->
            val sameId = segment.items.any { codec.callId(it) in nativeIds }
            nativeReplayValidator.conflictsWithBaseline(
                segment,
                record.baselineLogicalCount,
                allowed,
                expectedOffsets,
                sameId,
            )
        }
        if (conflict) return ReplayRewrite(null, "code-mode native discovery history was edited")
        val merged = replay.toMutableList()
        expected.forEach { (offset, items) ->
            val present = merged.any {
                val sameSlot = it.logicalOffset == offset && it.callbackId == null
                sameSlot && it.items == items
            }
            if (!present) merged += ResponsesCodeModeReplay(offset, null, items)
        }
        return ReplayRewrite(merged.sortedBy(ResponsesCodeModeReplay::logicalOffset))
    }

    private fun opaqueProblem(items: List<JsonElement>, record: CodeModeRecord): String? {
        val found = items.filter { ownership.isOpaque(it, record.outerCallId) }
        if (found.isEmpty()) return null
        return if (found == listOf(record.outer, codec.customOutput(record))) {
            null
        } else {
            "code-mode opaque history conflicts with its persisted result"
        }
    }

    private fun continuityIndexes(items: List<JsonElement>, boundary: Int, expected: List<JsonElement>): Set<Int> {
        if (expected.isEmpty()) return emptySet()
        val end = boundary + expected.size
        return if (end <= items.size && items.subList(boundary, end) == expected) {
            (boundary until end).toSet()
        } else {
            emptySet()
        }
    }

    private fun metadataProblem(record: CodeModeRecord): String? = when {
        record.metadataVersion != CODE_MODE_METADATA_VERSION -> "code-mode replay metadata is unavailable"
        record.baselineLogicalCount < 0 -> "code-mode replay metadata has an invalid logical boundary"
        record.baselineLogicalDigest.isEmpty() -> "code-mode replay metadata has no logical digest"
        record.baselineInputDigest.isEmpty() -> "code-mode replay metadata has no wire digest"
        record.nativeSegments.any { it.logicalOffset !in 0..record.baselineLogicalCount } ->
            "code-mode replay metadata has an invalid native offset"
        record.continuityReplay.any { it.logicalOffset !in 0..record.continuity.size } ->
            "code-mode replay metadata has an invalid continuity offset"
        else -> null
    }
}

/**
 * What a record OWNS inside a client history: its callbacks (function_call / function_call_output
 * under an owned call id), the opaque pair (the outer custom call and its output), and (V4-179)
 * its follow-up sequences — a result id's persisted media items, found VERBATIM and CONTIGUOUS
 * right after that id's function_call_output. Follow-up ownership is by exact position and bytes,
 * not by shape: a user image the client put anywhere else, or one that differs from what was
 * rendered, is the client's own content and stays extra. A legacy id (accepted before media was
 * captured, so absent from the map) owns nothing, and whatever the client's history carries for
 * it stays ordinary content, exactly as before this row.
 */
private class CodeModeOwnership(private val codec: CodexCodeModeHistoryCodec) {
    /** The indexes a rewrite keeps: everything the record does not own and that is not one of the
     *  [continuity] indexes — or null when owned history sits BEFORE the persisted boundary, which
     *  no rewrite can place. */
    fun retained(items: List<JsonElement>, record: CodeModeRecord, continuity: Set<Int>): Set<Int>? {
        val boundary = record.baselineLogicalCount
        val owned = (record.results.keys + record.pending.map(CodeModePending::clientId)).toSet()
        val ownedFollowUps = followUps(items, record)
        val ownedItemBefore = items.take(boundary).any { isCallback(it, owned) || isOpaque(it, record.outerCallId) }
        if (ownedItemBefore || ownedFollowUps.any { it < boundary }) return null
        return items.indices.filter { index ->
            val removable = isCallback(items[index], owned) ||
                isOpaque(items[index], record.outerCallId) ||
                index in ownedFollowUps
            index !in continuity && !removable
        }.toSet()
    }

    /** [candidateMedia]: this turn's follow-ups for results the record has not accepted yet. */
    fun followUps(
        items: List<JsonElement>,
        record: CodeModeRecord,
        candidateMedia: Map<String, List<JsonElement>> = emptyMap(),
    ): Set<Int> {
        val owned = mutableSetOf<Int>()
        items.forEachIndexed { index, element ->
            val expected = expectedAfter(element, record, candidateMedia)
            val end = index + 1 + expected.size
            val present = expected.isNotEmpty() && end <= items.size
            if (present && items.subList(index + 1, end) == expected) owned += (index + 1 until end)
        }
        return owned
    }

    fun isCallback(element: JsonElement, owned: Set<String>): Boolean {
        val item = element as? JsonObject
        return codec.string(item, CODE_MODE_FIELD_CALL_ID) in owned &&
            codec.string(item, CODE_MODE_FIELD_TYPE) in FUNCTION_TYPES
    }

    fun isOpaque(element: JsonElement, outerId: String): Boolean {
        val item = element as? JsonObject
        return codec.string(item, CODE_MODE_FIELD_CALL_ID) == outerId &&
            codec.string(item, CODE_MODE_FIELD_TYPE) in CUSTOM_TYPES
    }

    /** The sequence the record owns right after [element]: a result's own follow-ups after its
     *  function_call_output, or — once a history is already canonical (a second script in the same
     *  turn re-canonicalizes the body the first one posted) — the whole durable sequence after the
     *  record's custom output, so the rewrite removes and re-inserts it rather than doubling it. */
    private fun expectedAfter(
        element: JsonElement,
        record: CodeModeRecord,
        candidateMedia: Map<String, List<JsonElement>>,
    ): List<JsonElement> {
        val item = element as? JsonObject ?: return emptyList()
        val callId = codec.string(item, CODE_MODE_FIELD_CALL_ID)
        return when (codec.string(item, CODE_MODE_FIELD_TYPE)) {
            TYPE_FUNCTION_OUTPUT -> record.accepted.media(callId) ?: candidateMedia[callId].orEmpty()
            TYPE_CUSTOM_OUTPUT -> if (callId == record.outerCallId) record.accepted.durableMedia() else emptyList()
            else -> emptyList()
        }
    }
}

private class NativeReplayValidator {
    fun conflictsWithBaseline(
        segment: ResponsesCodeModeReplay,
        boundary: Int,
        allowed: Set<Pair<Int, List<JsonElement>>>,
        expectedOffsets: Set<Int>,
        sameId: Boolean,
    ): Boolean {
        val sameOffset = segment.logicalOffset in expectedOffsets
        val unexpected = (segment.logicalOffset to segment.items) !in allowed
        val precedesBoundary = segment.logicalOffset < boundary
        val targetsBaseline = segment.callbackId == null && unexpected
        val collides = sameId || sameOffset
        val invalidPosition = collides || precedesBoundary
        return targetsBaseline && invalidPosition
    }
}

private data class ProjectedRewrite(val input: ResponsesCodeModeInput?, val error: String? = null)
private data class ReplayRewrite(val items: List<ResponsesCodeModeReplay>?, val error: String? = null)
private val CUSTOM_TYPES = setOf("custom_tool_call", TYPE_CUSTOM_OUTPUT)
private const val TYPE_FUNCTION_OUTPUT = "function_call_output"
private val FUNCTION_TYPES = setOf("function_call", TYPE_FUNCTION_OUTPUT)
