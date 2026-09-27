// PORT-OF: server/src/codex/translate-request.mjs + grok/translate-request.mjs @ pre-public-port-baseline —
// Anthropic content blocks → Responses input items. One private helper per input-item family — the
// ported shape of translate-request.mjs's message/block walk, kept flat so no single handler nests
// the whole cascade. Split into siblings by input-item family (2026-08-17, concentration campaign):
// this file keeps the walk and the plain-content families; ResponsesInputParts.kt owns the shared
// item factories, ResponsesInputTools.kt the tool round-trip family, ResponsesReasoningInject.kt the
// inject-once seam. The class keeps its original name so history greps land. Every relocated member
// kept its identical name and argument list.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.AnthropicMessage
import splice.core.wire.ContentBlock
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
import splice.core.wire.ToolDefinition
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock
import splice.dialect.responses.ResponsesLiteShape
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.ResponsesReasoningInject

internal class ResponsesInputBuilder(
    private val quirks: ResponsesQuirks,
    private val loopGuardDirectives: Map<String, String> = emptyMap(),
) {

    private val parts = ResponsesInputParts(quirks.minImageEdgePx)
    private val tools = ResponsesInputTools(quirks, loopGuardDirectives)
    private val inject = ResponsesReasoningInject()
    private val liteShape = ResponsesLiteShape(quirks)

    internal fun appendMessage(
        sink: JsonArrayBuilder,
        msg: AnthropicMessage,
        opts: BuildOptions,
        declareByName: Map<String, ToolDefinition>,
    ) {
        msg.content.forEachIndexed { at, block ->
            if (block is TextBlock) {
                sink.add(parts.roleText(msg.role, block.text, phaseOf(msg, at, opts)))
            } else {
                appendBlock(sink, block, opts, declareByName)
            }
        }
    }

    /**
     * V4-335: the phase of the text at [at] in [msg]. OpenAI's reasoning guide: "If you replay
     * assistant history manually, preserve each original phase value. Missing or dropped phase can
     * cause preambles to be treated as final answers". Claude Code keeps no phase, so it is read off
     * the message: text a tool call follows in the same message was said on the way to that call
     * (commentary), and text no call follows is the answer (final_answer). An assistant message on
     * a lite turn only: never a user's, and the non-lite shape stays as it was. [ROLE_ASSISTANT] is
     * the Responses role's own const: the comparison is where the Anthropic role becomes it.
     */
    private fun phaseOf(msg: AnthropicMessage, at: Int, opts: BuildOptions): AssistantPhase? = when {
        msg.role != ROLE_ASSISTANT || !liteShape.isLite(opts) -> null
        msg.content.subList(at + 1, msg.content.size).any { it is ToolUseBlock } -> AssistantPhase.COMMENTARY
        else -> AssistantPhase.FINAL_ANSWER
    }

    private fun appendBlock(
        sink: JsonArrayBuilder,
        block: ContentBlock,
        opts: BuildOptions,
        declareByName: Map<String, ToolDefinition>,
    ) {
        when (block) {
            is TextBlock -> Unit // appendMessage writes text, with its phase
            is ImageBlock -> appendImage(sink, block)
            is DocumentBlock -> appendDocument(sink, block)
            is RedactedThinkingBlock -> inject.appendRedactedThinking(sink, block, opts)
            is ToolUseBlock -> tools.appendToolUse(sink, block, opts, declareByName)
            is ToolResultBlock -> tools.appendToolResult(sink, block)
            is ThinkingBlock -> Unit // visible thinking never rides back upstream
            is UnknownBlock -> Unit // unknown client blocks are dropped, never crash
        }
    }

    private fun appendDocument(sink: JsonArrayBuilder, block: DocumentBlock) {
        sink.add(
            parts.roleText(
                "user",
                "[document omitted by ${quirks.providerTag} proxy: " +
                    "${block.source?.mediaType ?: "unknown type"}]",
            ),
        )
    }

    private fun appendImage(sink: JsonArrayBuilder, block: ImageBlock) {
        // DR-155: an undersized image gets its OWN sentence. Falling through to the marker below
        // would print "unsupported source", which is false and undebuggable — the source is
        // perfectly supported and read cleanly; the BACKEND refuses images that small, and only
        // saying so tells the operator (and the model) what actually happened.
        parts.belowFloor(block.source)?.let { min ->
            val why = parts.floorReason(min)
            sink.add(parts.roleText("user", "[image omitted by ${quirks.providerTag} proxy: $why]"))
            return
        }
        val part = parts.imagePart(block.source)
        if (part != null) {
            sink.add(
                buildJsonObject {
                    put("role", "user")
                    put(FIELD_CONTENT, buildJsonArray { add(part) })
                },
            )
        } else {
            sink.add(parts.roleText("user", "[image omitted by ${quirks.providerTag} proxy: unsupported source]"))
        }
    }
}

// FIELD_CONTENT is deliberately private-per-file, matching ResponsesInputParts.kt's own copy — see
// that file's note on the pre-existing ResponsesHarvest.kt / ResponsesToolSearchController.kt collision.
private const val FIELD_CONTENT = "content"
