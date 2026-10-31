package splice.provider.codex.callbacks

import splice.core.wire.ContentBlock
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.MediaSource
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock

/** The V4-178 rendering, byte for byte: what a record written by the daemon before V4-179 holds as
 *  an accepted result's text. It exists only so that record's replays still compare equal; a marker
 *  the model reads comes from [splice.provider.codex.CodexCodeModeTurnBuilder]. */
internal class CodeModeLegacyMarkers {
    fun resultText(toolUseId: String, part: ContentBlock): String = when (part) {
        is TextBlock -> part.text
        is ImageBlock -> omitted(toolUseId, "image", part.source)
        is DocumentBlock -> omitted(toolUseId, "document", part.source)
        is RedactedThinkingBlock, is ThinkingBlock, is ToolResultBlock, is ToolUseBlock, is UnknownBlock ->
            omitted(toolUseId, "non-text content", null)
    }

    private fun omitted(toolUseId: String, kind: String, source: MediaSource?): String {
        val media = source?.mediaType?.takeIf { it.isNotEmpty() } ?: kind
        val size = (source?.data?.length?.let { ", $it base64 chars" } ?: source?.url?.let { ", url $it" }).orEmpty()
        return "[$kind omitted by splice code-mode from tool_result $toolUseId: $media$size. A " +
            "splice_exec script reads tool results as text only; call the tool outside code mode to see it]"
    }
}
