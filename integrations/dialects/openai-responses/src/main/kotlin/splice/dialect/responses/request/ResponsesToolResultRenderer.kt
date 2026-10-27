package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.ContentBlock
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock
import splice.dialect.responses.ResponsesQuirks

/** Supplies explicit omission text when a source block cannot ride a tool result directly. */
internal fun interface ToolResultOmission {
    fun text(block: UnknownBlock): String
}

/** Renders result content without participating in dynamic tool declaration or loading. */
internal class ResponsesToolResultRenderer(
    private val quirks: ResponsesQuirks,
    private val loopGuardDirectives: Map<String, String>,
    private val omission: ToolResultOmission,
) {
    private val parts = ResponsesInputParts(quirks.request.minImageEdgePx)
    private val documents = ResponsesDocumentParts(quirks)

    fun append(sink: JsonArrayBuilder, block: ToolResultBlock) {
        val structured = quirks.request.supportsStructuredToolOutput && block.content.any { it !is TextBlock }
        val output = if (structured) structuredOutput(block) else stringOutput(block)
        sink.add(
            buildJsonObject {
                put("type", "function_call_output")
                put("call_id", block.toolUseId)
                put("output", output)
            },
        )
    }

    private fun stringOutput(block: ToolResultBlock): JsonPrimitive {
        val text = fallbackToolResult(block)
        val loopGuarded = loopGuardDirectives[block.toolUseId]?.let { "$it\n\n$text" } ?: text
        // A tool's error verdict is content, not the Responses item's lifecycle status.
        val errorVerdict = block.isError?.let { "[is_error=$it]" }
        return JsonPrimitive(errorVerdict?.let { "$it\n$loopGuarded" } ?: loopGuarded)
    }

    private fun structuredOutput(block: ToolResultBlock): JsonArray = buildJsonArray {
        block.isError?.let { add(text("[is_error=$it]")) }
        loopGuardDirectives[block.toolUseId]?.let { add(text("$it\n\n")) }
        block.content.forEach { content -> toolResultParts(block, content).forEach { add(it) } }
    }

    private fun fallbackToolResult(result: ToolResultBlock): String = buildString {
        var previousOmission = false
        result.content.forEach { content ->
            val omitted = content !is TextBlock
            val separator = if (omitted || previousOmission) "\n" else ""
            if (isNotEmpty()) append(separator)
            append(fallbackText(result, content))
            previousOmission = omitted
        }
    }

    private fun fallbackText(result: ToolResultBlock, content: ContentBlock): String = when (content) {
        is TextBlock -> content.text
        is ImageBlock -> imageOmission(result, "structured tool output unsupported")
        is DocumentBlock -> documents.toolResult(content, result.toolUseId)
            .joinToString("\n") { (it.getValue(TOOL_RESULT_TEXT_FIELD) as JsonPrimitive).content }
        is UnknownBlock -> omission.text(content)
        is ThinkingBlock -> unsupportedText(result, "thinking")
        is RedactedThinkingBlock -> unsupportedText(result, "redacted_thinking")
        is ToolUseBlock -> unsupportedText(result, "tool_use")
        is ToolResultBlock -> unsupportedText(result, "tool_result")
    }

    private fun toolResultParts(result: ToolResultBlock, content: ContentBlock): List<JsonObject> = when (content) {
        is TextBlock -> listOf(text(content.text))
        is ImageBlock -> listOf(imagePart(result, content))
        is DocumentBlock -> documents.toolResult(content, result.toolUseId)
        is UnknownBlock -> listOf(text(omission.text(content)))
        is ThinkingBlock -> listOf(text(unsupportedText(result, "thinking")))
        is RedactedThinkingBlock -> listOf(text(unsupportedText(result, "redacted_thinking")))
        is ToolUseBlock -> listOf(text(unsupportedText(result, "tool_use")))
        is ToolResultBlock -> listOf(text(unsupportedText(result, "tool_result")))
    }

    private fun imagePart(result: ToolResultBlock, content: ImageBlock): JsonObject {
        val floor = parts.belowFloor(content.source)
        return if (floor != null) {
            text(imageOmission(result, parts.floorReason(floor)))
        } else {
            parts.imagePart(content.source) ?: text(imageOmission(result, UNSUPPORTED_SOURCE))
        }
    }

    private fun text(value: String): JsonObject = buildJsonObject {
        put("type", "input_text")
        put(TOOL_RESULT_TEXT_FIELD, value)
    }

    private fun imageOmission(result: ToolResultBlock, reason: String): String =
        "[1 image(s) from tool_result ${result.toolUseId} omitted by ${quirks.providerTag} proxy: $reason]"

    private fun unsupportedText(result: ToolResultBlock, kind: String): String =
        "[$kind block from tool_result ${result.toolUseId} omitted by ${quirks.providerTag} proxy: " +
            "unsupported in function_call_output]"
}

private const val TOOL_RESULT_TEXT_FIELD = "text"
