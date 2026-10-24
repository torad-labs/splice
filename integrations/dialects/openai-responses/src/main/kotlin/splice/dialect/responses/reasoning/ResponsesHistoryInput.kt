package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonArrayBuilder
import splice.core.reasoning.HISTORY_POSITION_PREFIX
import splice.core.wire.AnthropicMessage
import splice.core.wire.ContentBlock
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock
import splice.dialect.responses.request.BuildOptions

/** Rebuilds positions once per request, never per delta. A digest mismatch is a miss, never a guess.
 * Offsets locate possible boundaries inside text Claude Code coalesced; only a matching digest splits the wire. */
internal class ResponsesHistoryInput(private val opts: BuildOptions) {
    val cursor = ResponsesHistoryCursor(opts.reasoningHistory, opts.handoff.resultProofs())
    private val positions = opts.handoff.history().toMutableMap()
    private val offsets = positions.keys.mapNotNull {
        it.removePrefix(HISTORY_POSITION_PREFIX).substringBefore(':').toLongOrNull()
    }.toHashSet()
    private val orderedOffsets = offsets.sorted()
    private val inject = ResponsesReasoningInject()
    private val aliases = linkedMapOf<String, String>()

    /** Advance unaffected messages without building wire JSON. Only a verified interior boundary needs a live render. */
    fun advanceUnchanged(message: AnthropicMessage): Boolean {
        val start = cursor.history.offset
        val end = start + message.content.sumOf(::visibleSize)
        if (!hasInteriorOffset(start, end)) {
            message.content.forEach { cursor.append(message.role, it) }
            return true
        }
        val probe = cursor.fork()
        if (!advanceMessage(probe, message, end)) return false
        cursor.adopt(probe)
        return true
    }

    private fun advanceMessage(probe: ResponsesHistoryCursor, message: AnthropicMessage, end: Long): Boolean {
        for (block in message.content) {
            if (block is TextBlock) {
                if (!advanceText(probe, message.role, block.text, end)) return false
            } else {
                probe.append(message.role, block)
            }
            if (probe.history.offset < end && matches(probe)) return false
        }
        return true
    }

    fun inject(sink: JsonArrayBuilder) {
        val stable = cursor.history.key
        val current = positions.remove(stable).orEmpty()
        val legacy = cursor.legacyKey
        val previous = if (legacy == stable) emptyList() else positions.remove(legacy).orEmpty()
        if (previous.isNotEmpty()) aliases[legacy] = stable
        (previous + current).forEach { envelope ->
            opts.handoff.decode(envelope)?.let { inject.addReasoningOnce(sink, it, opts) }
        }
    }

    /** One promotion batch after all matching positions have been reconstructed, never one rewrite per item. */
    fun promote() {
        if (aliases.isEmpty() && cursor.pendingProofs.isEmpty()) return
        opts.handoff.historyAlias(ReasoningHistoryUpdate(aliases.toMap(), cursor.pendingProofs.toMap()))
        aliases.clear()
        cursor.pendingProofs.clear()
    }

    fun text(sink: JsonArrayBuilder, role: String, text: String, emit: ReasoningTextEmitter) {
        var hashed = 0
        var emitted = 0
        val start = cursor.history.offset
        for (end in 1..text.length) {
            if (start + end !in offsets) continue
            cursor.text(role, text.substring(hashed, end))
            hashed = end
            if (matches(cursor)) {
                emit(text.substring(emitted, end))
                emitted = end
                inject(sink)
            }
        }
        cursor.text(role, text.substring(hashed))
        if (emitted < text.length) emit(text.substring(emitted))
    }

    private fun advanceText(probe: ResponsesHistoryCursor, role: String, text: String, limit: Long): Boolean {
        var hashed = 0
        val start = probe.history.offset
        for (end in 1..text.length) {
            if (start + end !in offsets) continue
            probe.text(role, text.substring(hashed, end))
            hashed = end
            if (probe.history.offset < limit && matches(probe)) return false
        }
        probe.text(role, text.substring(hashed))
        return true
    }

    private fun matches(at: ResponsesHistoryCursor): Boolean {
        if (at.history.offset !in offsets) return false
        return positions.containsKey(at.history.key) || positions.containsKey(at.legacyKey)
    }

    private fun hasInteriorOffset(start: Long, end: Long): Boolean {
        if (positions.isEmpty()) return false
        val index = orderedOffsets.binarySearch(start + 1).let { if (it < 0) -it - 1 else it }
        val next = orderedOffsets.getOrNull(index) ?: return false
        return next < end
    }

    private fun visibleSize(block: ContentBlock): Long = when (block) {
        is TextBlock -> block.text.length.toLong()
        is ThinkingBlock, is RedactedThinkingBlock -> 0L
        is ToolUseBlock, is ToolResultBlock, is ImageBlock, is DocumentBlock, is UnknownBlock -> 1L
    }
}
