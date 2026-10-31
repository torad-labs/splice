package splice.dialect.responses.reasoning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import splice.core.reasoning.ReasoningHistory
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
            pendingProofs[boundary] = if (pendingProofs.containsKey(boundary) && pendingProofs[boundary] != actual) {
                null
            } else {
                actual
            }
        }
        history.block(role, "full_result_digest", JsonPrimitive(digest))
        // Pre-upgrade positions are accepted only against their exact original payload.
        legacy.block(role, "content", encoded)
    }

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
