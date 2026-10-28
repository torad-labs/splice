package splice.provider.codex.callbacks

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import splice.core.parse.AnthropicTurnBody
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
import splice.dialect.responses.request.ImageDisposition
import splice.dialect.responses.request.ResponsesToolResultMedia
import splice.provider.codex.CODE_MODE_CLIENT_ID_PREFIX
import splice.provider.codex.CodeModeLoadedTools
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeResultDisposition

/** Projects ordinary client callback values and media once, including the historical replay representation. */
internal class CodeModeClientResults(private val media: ResponsesToolResultMedia) {
    private val legacyMarkers = CodeModeLegacyMarkers()

    fun read(body: AnthropicTurnBody, loaded: CodeModeLoadedTools): List<CodeModeResult> = blocks(body).map { block ->
        val dispositions = media.followUps(block).dispositions.iterator()
        val output = block.content.joinToString("") { part -> resultText(loaded, block.toolUseId, part, dispositions) }
        val value = CodeModeClientToolValues.value(block)
        CodeModeResult(
            id = block.toolUseId,
            output = output,
            isError = block.isError == true,
            disposition = CodeModeResultDisposition.TOOL_OUTPUT,
            payload = value?.takeUnless { it == output }?.let(::JsonPrimitive),
        )
    }

    fun media(body: AnthropicTurnBody): Map<String, List<JsonElement>> =
        blocks(body).associate { block -> block.toolUseId to media.followUps(block).items }

    fun legacy(body: AnthropicTurnBody): List<CodeModeResult> = blocks(body).map { block ->
        CodeModeResult(
            id = block.toolUseId,
            output = block.content.joinToString("") { part -> legacyMarkers.resultText(block.toolUseId, part) },
            isError = block.isError == true,
        )
    }

    fun blocks(body: AnthropicTurnBody): List<ToolResultBlock> = body.typed.messages
        .flatMap { it.content }
        .filterIsInstance<ToolResultBlock>()
        .filter { it.toolUseId.startsWith(CODE_MODE_CLIENT_ID_PREFIX) }

    /** Text remains text. Every other part keeps an explicit marker, including the renderer's actual image verdict.
     *  A screenshot must never refuse every future replay or silently disappear from a script's result. */
    private fun resultText(
        loaded: CodeModeLoadedTools,
        toolUseId: String,
        part: ContentBlock,
        dispositions: Iterator<ImageDisposition>,
    ): String = when (part) {
        is TextBlock -> part.text
        is ImageBlock -> imageMarker(toolUseId, part.source, dispositions.next())
        is DocumentBlock -> omitted(toolUseId, "document", part.source, DOCUMENT_REASON)
        is UnknownBlock -> loaded.text(part)
            ?: omitted(toolUseId, "non-text content", null, DOCUMENT_REASON)
        is RedactedThinkingBlock, is ThinkingBlock, is ToolResultBlock, is ToolUseBlock ->
            omitted(toolUseId, "non-text content", null, DOCUMENT_REASON)
    }

    private fun imageMarker(toolUseId: String, source: MediaSource?, disposition: ImageDisposition): String =
        if (disposition.delivered) {
            "[image from tool_result $toolUseId: ${describe("image", source)}, delivered to the model " +
                "beside this script's output; a splice_exec script itself reads tool results as text only]"
        } else {
            omitted(toolUseId, "image", source, disposition.reason.orEmpty())
        }

    private fun omitted(toolUseId: String, kind: String, source: MediaSource?, why: String): String =
        "[$kind omitted by splice code-mode from tool_result $toolUseId: ${describe(kind, source)} ($why); " +
            "a splice_exec script reads tool results as text only]"

    private fun describe(kind: String, source: MediaSource?): String {
        val media = source?.mediaType?.takeIf { it.isNotEmpty() } ?: kind
        val size = (source?.data?.length?.let { ", $it base64 chars" } ?: source?.url?.let { ", url $it" }).orEmpty()
        return "$media$size"
    }
}

/** The ordinary wire has no document or unknown-block shape on this path, so their markers name the omission. */
private const val DOCUMENT_REASON = "this content type cannot ride to the model on this path"
