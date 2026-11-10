package splice.dialect.responses.reasoning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import splice.core.reasoning.ReasoningHistory
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

/** The same visible grammar is used when capturing output and rebuilding the client's input. */
internal class ResponsesHistoryCursor(
    val history: ReasoningHistory = ReasoningHistory(),
    private val proofs: Map<String, String?> = emptyMap(),
    private val legacy: ReasoningHistory = ReasoningHistory(),
) {
    val legacyKey: String get() = legacy.key
    val pendingProofs: MutableMap<String, String?> = linkedMapOf()

    /** The result proofs the message being stepped over makes, while a memo records that step. */
    private var stepProofs: MutableList<Pair<String, String>>? = null

    fun legacySnapshot(): ReasoningHistory = legacy.copy()

    /** How far stepping over [message] moves the position: text by its length, every other kept block by one. */
    fun visibleSize(message: AnthropicMessage): Long = message.content.sumOf { block ->
        when (block) {
            is TextBlock -> block.text.length.toLong()
            is ThinkingBlock, is RedactedThinkingBlock -> 0L
            is ToolUseBlock, is ToolResultBlock, is ImageBlock, is DocumentBlock, is UnknownBlock -> 1L
        }
    }

    /** Steps over a whole message. An earlier request's identical step is taken again rather than hashed again;
     *  a cleared result's digest depends on this request's proofs, so a message holding one is always hashed. */
    fun advance(message: AnthropicMessage, memo: HistoryMemoWalk?) {
        if (memo == null || readsProofs(message)) {
            message.content.forEach { append(message.role, it) }
            return
        }
        val before = "${history.key} ${legacy.key}"
        val step = memo.find(before, message)
        if (step != null) {
            history.restore(step.history)
            legacy.restore(step.legacy)
            step.proofs.forEach { (boundary, actual) -> prove(boundary, actual) }
            return
        }
        val proofs = ArrayList<Pair<String, String>>()
        stepProofs = proofs
        try {
            message.content.forEach { append(message.role, it) }
        } finally {
            stepProofs = null
        }
        memo.record(before, message, this, proofs)
    }

    fun text(role: String, text: String) {
        history.text(role, text)
        legacy.text(role, text)
    }

    /** A speculative message walk owns only its new proofs, never a copy of the cumulative proof table. */
    fun fork(): ResponsesHistoryCursor = ResponsesHistoryCursor(history.copy(), proofs, legacy.copy())

    fun adopt(other: ResponsesHistoryCursor) {
        history.restore(other.history)
        legacy.restore(other.legacy)
        other.pendingProofs.forEach { (key, digest) ->
            pendingProofs[key] = if (pendingProofs.containsKey(key) && pendingProofs[key] != digest) null else digest
        }
    }

    fun append(role: String, block: ContentBlock) {
        when (block) {
            is TextBlock -> text(role, block.text)
            is ToolUseBlock -> block(role, "tool_use", block)
            is ThinkingBlock, is RedactedThinkingBlock -> Unit
            is ToolResultBlock -> result(role, block)
            is ImageBlock, is DocumentBlock, is UnknownBlock -> block(role, "content", block)
        }
    }

    private fun result(role: String, block: ToolResultBlock) {
        val encoded = Json.encodeToJsonElement(ContentBlock.serializer(), block)
        val boundary = ReasoningHistory().apply {
            block(role, "before_result", JsonPrimitive(history.key))
            block(
                role,
                "result_identity",
                Json.encodeToJsonElement(ContentBlock.serializer(), block.copy(content = emptyList())),
            )
        }.key
        val actual = ReasoningHistory().apply { block(role, "full_result", encoded) }.key
        val cleared = isClearedResult(block)
        val digest = if (cleared) proofs[boundary] ?: actual else actual
        if (!cleared) {
            prove(boundary, actual)
            stepProofs?.add(boundary to actual)
        }
        history.block(role, "full_result_digest", JsonPrimitive(digest))
        // Pre-upgrade positions are accepted only against their exact original payload.
        legacy.block(role, "content", encoded)
    }

    private fun prove(boundary: String, actual: String) {
        pendingProofs[boundary] = if (pendingProofs.containsKey(boundary) && pendingProofs[boundary] != actual) {
            null
        } else {
            actual
        }
    }

    private fun readsProofs(message: AnthropicMessage): Boolean =
        message.content.filterIsInstance<ToolResultBlock>().any(::isClearedResult)

    private fun isClearedResult(block: ToolResultBlock): Boolean {
        val content = block.content.singleOrNull()
        return content is TextBlock && content.text == "[Old tool result content cleared]"
    }

    private fun block(role: String, kind: String, block: ContentBlock) {
        val encoded = Json.encodeToJsonElement(ContentBlock.serializer(), block)
        history.block(role, kind, encoded)
        legacy.block(role, kind, encoded)
    }
}
