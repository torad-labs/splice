package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.add
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
import splice.dialect.responses.request.ResponsesAssistantText

/** Rebuilds positions once per request, never per delta. A digest mismatch is a miss, never a guess.
 * Offsets locate possible boundaries inside text Claude Code coalesced; only a matching digest splits the wire. */
internal class ResponsesHistoryInput(
    private val opts: BuildOptions,
    messages: List<AnthropicMessage> = emptyList(),
) {
    val cursor = ResponsesHistoryCursor(opts.reasoningHistory, opts.handoff.resultProofs())
    private val saved = opts.handoff.history()
    private val records = ResponsesAssistantPhaseRecords()
    private val phases = ResponsesAssistantPhaseHistory(messages, records.read(saved), opts.handoff.resultProofs())
    private val positions = records.reasoningOnly(saved).toMutableMap()
    private val offsets = (
        positions.keys.mapNotNull {
            it.removePrefix(HISTORY_POSITION_PREFIX).substringBefore(':').toLongOrNull()
        } + phases.offsets
        ).toHashSet()
    private val orderedOffsets = offsets.sorted()
    private val inject = ResponsesReasoningInject()
    private val aliases = linkedMapOf<String, String>()

    /** A known native phase is request state, so an older inferred fragment must not replace it. */
    fun advanceUnchanged(message: AnthropicMessage): Boolean {
        val start = cursor.history.offset
        val end = start + message.content.sumOf(::visibleSize)
        if (message.role == "assistant" && phases.affects(start, end)) return false
        if (!hasInteriorOffset(start, end)) {
            message.content.forEach { cursor.append(message.role, it) }
            return true
        }
        val probe = cursor.fork()
        val advanced = advanceMessage(probe, message, end)
        if (advanced) cursor.adopt(probe)
        return advanced
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
                emitText(sink, role, text.substring(emitted, end), start + emitted, emit)
                emitted = end
                inject(sink)
            }
        }
        cursor.text(role, text.substring(hashed))
        if (emitted < text.length) emitText(sink, role, text.substring(emitted), start + emitted, emit)
    }

    private fun emitText(sink: JsonArrayBuilder, role: String, text: String, start: Long, emit: ReasoningTextEmitter) {
        val phase = if (role == "assistant") phases.phase(start, start + text.length) else null
        if (phase == null) emit(text) else sink.add(ResponsesAssistantText.item(text, phase))
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
        return positions.containsKey(at.history.key) || positions.containsKey(at.legacyKey) ||
            at.history.offset in phases.offsets
    }

    private fun hasInteriorOffset(start: Long, end: Long): Boolean {
        if (offsets.isEmpty()) return false
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
