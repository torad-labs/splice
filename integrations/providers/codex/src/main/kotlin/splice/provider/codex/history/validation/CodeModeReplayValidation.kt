package splice.provider.codex.history.validation

import kotlinx.serialization.json.JsonElement
import splice.dialect.responses.request.ResponsesCodeModeReplay
import splice.provider.codex.CodeModeCallReplay
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeHistoryCodec
import splice.provider.codex.history.CodeModeOwnership
internal class CodeModeReplayValidation(
    private val codec: CodexCodeModeHistoryCodec,
    private val ownership: CodeModeOwnership,
) {
    fun mediaProblem(record: CodeModeRecord, replayMedia: Map<String, List<JsonElement>>): String? {
        val changed = replayMedia.keys.firstOrNull { id ->
            record.accepted.media(id)?.let { captured -> captured != replayMedia.getValue(id) } == true
        } ?: return null
        return "code-mode result '$changed' is replayed with media that differ from what its record captured"
    }

    fun opaqueProblem(items: List<JsonElement>, record: CodeModeRecord): String? {
        val found = items.filter { ownership.isOpaque(it, record.origin.outerCallId) }
        if (found.isEmpty()) return null
        val output = codec.customOutput(record)
        val replayed = listOf(CodeModeCallReplay.item(record), output) + record.progress.nativeNotifications
        val initial = listOf(record.origin.outer, output) + record.progress.nativeNotifications
        return if (found == replayed || found == initial) {
            null
        } else {
            "code-mode opaque history conflicts with its persisted result"
        }
    }

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
