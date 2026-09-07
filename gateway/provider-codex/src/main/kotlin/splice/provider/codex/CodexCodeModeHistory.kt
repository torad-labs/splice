// NEW: canonicalizes completed code-mode records at their persisted logical history boundaries.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.dialect.responses.ResponsesCodeModeInput
import splice.dialect.responses.ResponsesCodeModeReplay

internal class CodexCodeModeHistory(json: Json) {
    private val codec = CodexCodeModeHistoryCodec(json)
    private val nativeReplayValidator = NativeReplayValidator()

    fun inputBoundary(bodyJson: String): CodeModeInputBoundary? = codec.inputBoundary(bodyJson)

    fun hasExtraContent(bodyJson: String, record: CodeModeRecord): Boolean {
        val input = codec.root(bodyJson)?.second ?: return true
        val projected = codec.conversation(codec.projection.project(input)).body
        val validBaseline = codec.validFullPrefix(input, record) ||
            codec.validPrefix(projected.logicalItems, record)
        if (!validBaseline) return true
        val owned = (record.results.keys + record.pending.map(CodeModePending::clientId)).toSet()
        val tail = projected.logicalItems.drop(record.baselineLogicalCount)
        val afterContinuity = if (tail.take(record.continuity.size) == record.continuity) {
            tail.drop(record.continuity.size)
        } else {
            tail
        }
        val logicalExtra = afterContinuity.any { !isOwnedCallback(it, owned) }
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
     */
    fun canonicalize(bodyJson: String, records: List<CodeModeRecord>): CodeModeRewrite {
        val root = codec.root(bodyJson)
            ?: return CodeModeRewrite(null, "code mode requires a Responses input array")
        val conversation = codec.conversation(codec.projection.project(root.second))
        var body = conversation.body
        val omitted = mutableListOf<CodeModeOmission>()
        records.forEach { record ->
            val rewritten = canonicalizeRecord(body, record)
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

    private fun canonicalizeRecord(input: ResponsesCodeModeInput, record: CodeModeRecord): ProjectedRewrite {
        metadataProblem(record)?.let { return ProjectedRewrite(null, it) }
        if (!codec.validPrefix(input.logicalItems, record)) {
            return ProjectedRewrite(null, "code-mode logical history does not match its persisted baseline")
        }
        return rewriteRecord(input, record)
    }

    private fun rewriteRecord(input: ResponsesCodeModeInput, record: CodeModeRecord): ProjectedRewrite {
        val boundary = record.baselineLogicalCount
        val owned = (record.results.keys + record.pending.map(CodeModePending::clientId)).toSet()
        val before = input.logicalItems.take(boundary)
        if (before.any { isOwnedCallback(it, owned) || isOpaque(it, record.outerCallId) }) {
            return ProjectedRewrite(null, "code-mode owned history appears before its persisted boundary")
        }
        opaqueProblem(input.logicalItems.drop(boundary), record)?.let { return ProjectedRewrite(null, it) }
        val continuity = continuityIndexes(input.logicalItems, boundary, record.continuity)
        val retained = input.logicalItems.indices.filter { index ->
            val removable = isOwnedCallback(input.logicalItems[index], owned) ||
                isOpaque(input.logicalItems[index], record.outerCallId)
            index !in continuity && !removable
        }.toSet()
        val canonical = record.continuity + record.outer + codec.customOutput(record)
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
        val found = items.filter { isOpaque(it, record.outerCallId) }
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

    private fun isOwnedCallback(element: JsonElement, owned: Set<String>): Boolean {
        val item = element as? JsonObject
        return codec.string(item, CODE_MODE_FIELD_CALL_ID) in owned &&
            codec.string(item, CODE_MODE_FIELD_TYPE) in FUNCTION_TYPES
    }

    private fun isOpaque(element: JsonElement, outerId: String): Boolean {
        val item = element as? JsonObject
        return codec.string(item, CODE_MODE_FIELD_CALL_ID) == outerId &&
            codec.string(item, CODE_MODE_FIELD_TYPE) in CUSTOM_TYPES
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
private val CUSTOM_TYPES = setOf("custom_tool_call", "custom_tool_call_output")
private val FUNCTION_TYPES = setOf("function_call", "function_call_output")
