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
    fun append(role: String, block: ContentBlock) {
        when (block) {
            is TextBlock -> history.text(role, block.text)
            is ToolUseBlock -> history.block(
                role,
                "tool_use",
                Json.encodeToJsonElement(ContentBlock.serializer(), block),
            )
            is ThinkingBlock, is RedactedThinkingBlock -> Unit
            is ImageBlock, is DocumentBlock, is ToolResultBlock, is UnknownBlock ->
                history.block(role, "content", Json.encodeToJsonElement(ContentBlock.serializer(), block))
        }
    }
}
