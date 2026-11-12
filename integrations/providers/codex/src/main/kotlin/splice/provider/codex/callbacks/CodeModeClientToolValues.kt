package splice.provider.codex.callbacks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.MediaSource
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock
import splice.upstream.codemode.CodeModeToolOutputs

/**
 * Generic function outputs use Codex's content-item value projection; unsupported client forms retain their
 * explicit markers.
 */
internal object CodeModeClientToolValues {
    fun value(result: ToolResultBlock): String? {
        if (result.content.any { it !is TextBlock && it !is ImageBlock }) return null
        val items = result.content.map { part ->
            when (part) {
                is TextBlock -> buildJsonObject { put("type", "input_text"); put("text", part.text) }
                is ImageBlock -> image(part.source)
                is DocumentBlock, is RedactedThinkingBlock, is ThinkingBlock,
                is ToolResultBlock, is ToolUseBlock, is UnknownBlock,
                -> null
            }
        }
        return if (items.any { it == null }) null else CodeModeToolOutputs.content(items.filterNotNull())
    }

    private fun image(source: MediaSource?): JsonObject? {
        val url = when (source?.type) {
            "base64" -> {
                val media = source.mediaType?.takeIf(String::isNotBlank)
                val data = source.data
                if (media == null || data == null) null else "data:$media;base64,$data"
            }
            "url" -> source.url
            else -> null
        }
        return url?.let { buildJsonObject { put("type", "input_image"); put("image_url", it) } }
    }
}
