package splice.dialect.responses.request

import kotlinx.serialization.json.JsonPrimitive
import splice.core.wire.AnthropicRequest
import splice.core.wire.ContentBlock

/** The source citation option has no equivalent on ordinary Responses document input. */
internal object ResponsesDocumentAdmission {
    private val enabled = JsonPrimitive(true)

    fun requiresCitations(body: AnthropicRequest): Boolean =
        body.messages.any { message -> message.content.any(::requiresCitations) }

    private fun requiresCitations(block: ContentBlock): Boolean = when (block) {
        is ContentBlock.DocumentBlock -> block.citations?.get("enabled") == enabled
        is ContentBlock.ToolResultBlock -> block.content.any(::requiresCitations)
        is ContentBlock.TextBlock,
        is ContentBlock.ImageBlock,
        is ContentBlock.ThinkingBlock,
        is ContentBlock.RedactedThinkingBlock,
        is ContentBlock.ToolUseBlock,
        is ContentBlock.UnknownBlock,
        -> false
    }
}
