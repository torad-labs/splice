package splice.dialect.responses.reasoning

import splice.core.wire.AnthropicMessage
import splice.core.wire.ContentBlock
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
import splice.dialect.responses.request.AssistantPhase

/** A phase is restored only when both ends match this exact visible transcript, across client text seams. */
internal class ResponsesAssistantPhaseHistory(
    messages: List<AnthropicMessage>,
    records: List<AssistantPhaseRecord>,
    proofs: Map<String, String?>,
) {
    private val explicit = explicitRanges(messages)
    private val verified = verified(messages, records, proofs)
    val offsets: Set<Long> = verified.flatMap { listOf(it.start, it.end) }.toSet()

    fun affects(start: Long, end: Long): Boolean = verified.any { it.start < end && start < it.end }

    fun phase(start: Long, end: Long): AssistantPhase? {
        if (explicit.any { (before, after) -> before < end && start < after }) return null
        val record = verified.firstOrNull { it.start <= start && end <= it.end } ?: return null
        return AssistantPhase.entries.firstOrNull { it.wire == record.phase }
    }

    private fun verified(
        messages: List<AnthropicMessage>,
        records: List<AssistantPhaseRecord>,
        proofs: Map<String, String?>,
    ): List<AssistantPhaseRecord> {
        if (records.isEmpty()) return emptyList()
        val boundaries = records.flatMap { listOf(it.start, it.end) }.distinct().sorted()
        val keys = PhaseHistoryKeyScan(boundaries, proofs).capture(messages)
        val matching = records.distinct().filter { keys[it.start] == it.before && keys[it.end] == it.after }
        // An identical visible branch with contradictory upstream provenance proves neither phase.
        return matching.filter { candidate ->
            matching.none { other ->
                candidate.phase != other.phase && candidate.start < other.end && other.start < candidate.end
            }
        }
    }

    private fun explicitRanges(messages: List<AnthropicMessage>): List<Pair<Long, Long>> {
        var offset = 0L
        return buildList {
            for (message in messages) {
                val start = offset
                offset += message.content.sumOf(::visibleSize)
                if (message.role == "assistant" && AssistantPhase.entries.any { it.wire == message.phase }) {
                    add(start to offset)
                }
            }
        }
    }

    private fun visibleSize(block: ContentBlock): Long = when (block) {
        is TextBlock -> block.text.length.toLong()
        is ThinkingBlock, is RedactedThinkingBlock -> 0L
        is ContentBlock.ToolUseBlock, is ContentBlock.ToolResultBlock, is ContentBlock.ImageBlock,
        is ContentBlock.DocumentBlock, is ContentBlock.UnknownBlock,
        -> 1L
    }
}

/** Owns one monotonic scan of the requested digest boundaries, independently of phase selection. */
private class PhaseHistoryKeyScan(
    private val boundaries: List<Long>,
    proofs: Map<String, String?>,
) {
    private val cursor = ResponsesHistoryCursor(proofs = proofs)
    private val result = mutableMapOf<Long, String>()
    private val wanted = boundaries.toSet()
    private var boundary = 0

    fun capture(messages: List<AnthropicMessage>): Map<Long, String> {
        remember()
        for (message in messages) {
            for (block in message.content) append(message.role, block)
        }
        return result
    }

    private fun append(role: String, block: ContentBlock) {
        if (block is TextBlock) {
            appendText(role, block.text)
        } else {
            cursor.append(role, block)
        }
        remember()
    }

    private fun appendText(role: String, text: String) {
        val start = cursor.history.offset
        val end = start + text.length
        var from = 0
        while (boundary < boundaries.size && boundaries[boundary] <= end) {
            val offset = boundaries[boundary++]
            if (offset > cursor.history.offset) {
                val to = (offset - start).toInt()
                cursor.text(role, text.substring(from, to))
                from = to
                result[offset] = cursor.history.key
            }
        }
        cursor.text(role, text.substring(from))
    }

    private fun remember() {
        if (cursor.history.offset in wanted) result[cursor.history.offset] = cursor.history.key
    }
}
