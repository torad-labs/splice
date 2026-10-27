package splice.dialect.responses.request

import splice.core.wire.AnthropicRequest
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

/** The Anthropic dynamic-tool state that changes the Responses request surface. */
internal object ResponsesDynamicToolChanges {
    internal data class Availability(
        val initiallyAvailable: List<ToolDefinition>,
        val currentNames: List<String>,
        val requiresRestriction: Boolean,
        val hasUnrepresentableRemoval: Boolean,
    )

    /** Source-deferred definitions stay known for later references but are not offered at the first boundary. */
    fun initiallyAvailable(tools: List<ToolDefinition>): List<ToolDefinition> =
        tools.filterNot { it.deferLoading }

    /**
     * The public Responses request keeps its initial declarations stable, then constrains the next
     * response with allowed_tools when source history changed what is callable. Ordered source
     * additions update the known schema catalog, while bare result references use UnknownBlock's
     * validated loadedTool recognizer instead of interpreting result text.
     */
    fun availability(body: AnthropicRequest): Availability {
        val state = SourceState(body.tools)
        body.messages.forEach { message ->
            message.content.forEach { block -> state.accept(message.role, block) }
        }
        return state.availability()
    }

    /** One source-ordered walk owns the known definitions and the currently callable names. */
    private class SourceState(tools: List<ToolDefinition>) {
        private val known = tools.associateByTo(linkedMapOf()) { it.name }
        private val initial = initiallyAvailable(tools)
        private val current = initial.mapTo(linkedSetOf()) { it.name }
        private var hasUnrepresentableRemoval = false

        fun accept(role: String, block: ContentBlock) {
            when (block) {
                is ToolResultBlock -> loadReferences(block)
                is UnknownBlock -> if (role == ResponsesContextMessage.CLIENT_ROLE) applyChange(block)
                is DocumentBlock,
                is ImageBlock,
                is RedactedThinkingBlock,
                is TextBlock,
                is ThinkingBlock,
                is ToolUseBlock,
                -> Unit
            }
        }

        private fun applyChange(block: UnknownBlock) {
            when (block.type) {
                SOURCE_TOOL_ADDITION -> ResponsesSourceToolDefinitions.fromAddition(block, known)?.let { tool ->
                    known[tool.name] = tool
                    current.add(tool.name)
                }
                SOURCE_TOOL_REMOVAL -> remove(block)
            }
        }

        private fun remove(block: UnknownBlock) {
            val tool = ResponsesSourceToolDefinitions.fromAddition(block, known)
            if (tool == null || tool.name !in known) {
                hasUnrepresentableRemoval = true
            } else {
                current.remove(tool.name)
            }
        }

        private fun loadReferences(block: ToolResultBlock) {
            block.content.asSequence()
                .filterIsInstance<UnknownBlock>()
                .mapNotNull { it.loadedTool }
                .mapNotNull(known::get)
                .forEach { tool -> current.add(tool.name) }
        }

        fun availability(): Availability {
            val currentNames = known.values.filter { it.name in current }.map { it.name }
            return Availability(
                initiallyAvailable = initial,
                currentNames = currentNames,
                requiresRestriction = initial.map { it.name } != currentNames,
                hasUnrepresentableRemoval = hasUnrepresentableRemoval,
            )
        }
    }
}
