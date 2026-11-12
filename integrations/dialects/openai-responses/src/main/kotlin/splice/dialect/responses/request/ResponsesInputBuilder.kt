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
import splice.dialect.responses.reasoning.ReasoningTextEmitter
import splice.dialect.responses.reasoning.ResponsesHistoryInput
import splice.dialect.responses.reasoning.ResponsesReasoningInject
import splice.upstream.ToolNameShortener

internal class ResponsesInputBuilder(
    private val quirks: ResponsesQuirks,
    private val loopGuardDirectives: Map<String, String> = emptyMap(),
    names: ToolNameShortener = ToolNameShortener(),
    private val declareByName: Map<String, ToolDefinition> = emptyMap(),
    private val knownTools: MutableMap<String, ToolDefinition> = mutableMapOf(),
) {

    private val parts = ResponsesInputParts(quirks.request.minImageEdgePx)
    private val documents = ResponsesDocumentParts(quirks)
    private val tools = ResponsesInputTools(quirks, loopGuardDirectives, names)
    private val inject = ResponsesReasoningInject()
    private val liteShape = ResponsesLiteShape(quirks)

    internal fun appendMessage(
        sink: JsonArrayBuilder,
        msg: AnthropicMessage,
        opts: BuildOptions,
        history: ResponsesHistoryInput? = null,
        futureAssistantToolUse: Boolean = false,
    ) {
        val context = msg.role == ResponsesContextMessage.CLIENT_ROLE && liteShape.isLite(opts)
        msg.content.forEachIndexed { at, block ->
            history?.inject(sink)
            if (block is TextBlock) {
                val emit = ReasoningTextEmitter { text ->
                    sink.add(
                        if (context) {
                            ResponsesContextMessage.item(text)
                        } else {
                            parts.roleText(msg.role, text, phaseOf(msg, at, futureAssistantToolUse))
                        },
                    )
                }
                if (history == null) emit(block.text) else history.text(sink, msg.role, block.text, emit)
            } else {
                appendBlock(sink, msg.role, block, opts)
                history?.cursor?.append(msg.role, block)
            }
            history?.inject(sink)
        }
    }

    /**
     * The standard Responses phase for historical assistant text. A public Responses input item's
     * known original phase wins over inference. Claude Code retains no phase, so text before a later
     * function call is commentary and text with no later call is the final answer. The call may be
     * in a later assistant message when the client split one turn.
     */
    private fun phaseOf(
        msg: AnthropicMessage,
        at: Int,
        futureAssistantToolUse: Boolean,
    ): AssistantPhase? {
        if (msg.role != ROLE_ASSISTANT) return null
        AssistantPhase.entries.firstOrNull { it.wire == msg.phase }?.let { return it }
        return if (futureAssistantToolUse || msg.content.subList(at + 1, msg.content.size).any { it is ToolUseBlock }) {
            AssistantPhase.COMMENTARY
        } else {
            AssistantPhase.FINAL_ANSWER
        }
    }

    private fun appendBlock(
        sink: JsonArrayBuilder,
        role: String,
        block: ContentBlock,
        opts: BuildOptions,
    ) {
        when (block) {
            is TextBlock -> Unit // appendMessage writes text, with its phase
            is ImageBlock -> appendImage(sink, block)
            is DocumentBlock -> appendDocument(sink, block)
            is RedactedThinkingBlock -> inject.appendRedactedThinking(sink, block, opts)
            is ToolUseBlock -> tools.appendToolUse(sink, block, opts, declareByName)
            is ToolResultBlock -> appendToolResult(sink, block, opts)
            is ThinkingBlock -> Unit // visible thinking never rides back upstream
            is UnknownBlock -> appendUnknown(sink, role, block)
        }
    }

    private fun appendToolResult(sink: JsonArrayBuilder, block: ToolResultBlock, opts: BuildOptions) {
        tools.appendToolResult(sink, block)
        // A source tool search loads its referenced schema only after this result. Lite's
        // outer executor owns its nested catalog, so it must not receive a direct function.
        if (!liteShape.isLite(opts)) {
            block.content.filterIsInstance<UnknownBlock>().forEach { loaded ->
                tools.appendLoadedTool(sink, loaded, knownTools)
            }
        }
    }

    /** A block kind splice does not enumerate (search results, server tool results from a session that began on
     *  another head) cannot ride this wire; it leaves an honest marker, except source removals, whose current
     *  callable surface rides the request's tool list, or its allowed_tools on a head that takes changes inline. */
    private fun appendUnknown(
        sink: JsonArrayBuilder,
        role: String,
        block: UnknownBlock,
    ) {
        when {
            role == ResponsesContextMessage.CLIENT_ROLE && block.type == SOURCE_TOOL_ADDITION ->
                tools.appendToolAddition(sink, block, knownTools)
            role == ResponsesContextMessage.CLIENT_ROLE && block.type == SOURCE_TOOL_REMOVAL ->
                Unit // the tool list, or request-level allowed_tools, preserves the current direct-function surface
            else -> sink.add(parts.roleText("user", tools.omission(block)))
        }
    }

    private fun appendDocument(sink: JsonArrayBuilder, block: DocumentBlock) {
        val content = documents.message(block)
        if (content == null) {
            sink.add(
                parts.roleText(
                    "user",
                    "[document omitted by ${quirks.providerTag} proxy: " +
                        "${block.source?.mediaType ?: "unknown type"}]",
                ),
            )
        } else if (content.isNotEmpty()) {
            sink.add(
                buildJsonObject {
                    put("role", "user")
                    put(FIELD_CONTENT, buildJsonArray { content.forEach { add(it) } })
                },
            )
        }
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
// that file's note on the pre-existing ResponsesHarvest.kt / ResponsesToolSearchPolicy.kt collision.
private const val FIELD_CONTENT = "content"
