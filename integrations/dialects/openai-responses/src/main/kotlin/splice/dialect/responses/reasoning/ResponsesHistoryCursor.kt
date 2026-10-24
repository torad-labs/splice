package splice.dialect.responses.reasoning

import kotlinx.serialization.json.Json
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
internal class ResponsesHistoryCursor(val history: ReasoningHistory = ReasoningHistory()) {
    private val legacy = ReasoningHistory()
    val legacyKey: String get() = legacy.key

    fun text(role: String, text: String) {
        history.text(role, text)
        legacy.text(role, text)
    }

    fun append(role: String, block: ContentBlock) {
        when (block) {
            is TextBlock -> text(role, block.text)
            is ToolUseBlock -> block(role, "tool_use", block)
            is ThinkingBlock, is RedactedThinkingBlock -> Unit
            // Microcompaction clears old result content without changing the call it answered.
            // Hash a copy: keep the call id and explicit failure verdict, never restore cleared payloads.
            // The preceding tool_use still hashes its complete name and arguments.
            is ToolResultBlock -> {
                history.block(
                    role,
                    "content",
                    Json.encodeToJsonElement(ContentBlock.serializer(), block.copy(content = emptyList())),
                )
                // Read pre-upgrade positions only against the exact original result payload.
                legacy.block(role, "content", Json.encodeToJsonElement(ContentBlock.serializer(), block))
            }
            is ImageBlock, is DocumentBlock, is UnknownBlock -> block(role, "content", block)
        }
    }

    private fun block(role: String, kind: String, block: ContentBlock) {
        val encoded = Json.encodeToJsonElement(ContentBlock.serializer(), block)
        history.block(role, kind, encoded)
        legacy.block(role, kind, encoded)
    }
}
