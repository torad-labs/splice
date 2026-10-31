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

/** Pure source-tool state shared by direct conversion and provider-owned nested catalogs. */
public object ResponsesDynamicToolChanges {
    /** Direct availability and catalog knowledge are separate: source deferral is not native withdrawal. */
    public data class Availability(
        public val initiallyAvailable: List<ToolDefinition>,
        public val currentNames: List<String>,
        public val hasUnrepresentableRemoval: Boolean,
        public val knownDefinitions: List<ToolDefinition>,
        public val withdrawnNames: Set<String>,
        public val loadedDefinitions: List<LoadedDefinition>,
        /** The tools the model may call now, each with the schema the conversation last gave it: the initial ones
         *  first, then each later one in the order it first became callable, so the list grows at its end. */
        public val currentDefinitions: List<ToolDefinition>,
    ) {
        /** A native provider may expose deferred definitions, but never a withdrawn one. */
        public val currentCatalog: List<ToolDefinition> = knownDefinitions.filterNot { it.name in withdrawnNames }

        /** The tools callable now are not the ones offered at the start. */
        public val requiresRestriction: Boolean = initiallyAvailable.map { it.name } != currentNames
    }

    /** The exact source block pins the schema at that boundary, even when identical references recur. */
    public data class LoadedDefinition(public val reference: UnknownBlock, public val definition: ToolDefinition)

    /** Source-deferred definitions stay known for later references but are not initially offered directly. */
    public fun initiallyAvailable(tools: List<ToolDefinition>): List<ToolDefinition> =
        tools.filterNot { it.deferLoading }

    /**
     * Keep initial declarations stable and fold current direct availability in source order. Catalog
     * knowledge retains native deferred definitions separately, and reference snapshots never acquire
     * a schema from a later addition. This state selects no transport, executor or provider strategy.
     */
    public fun availability(body: AnthropicRequest): Availability {
        val state = SourceState(body.tools)
        body.messages.forEach { message ->
            message.content.forEach { block -> state.accept(message.role, block) }
        }
        return state.availability()
    }

    /** One source-ordered walk owns schemas, direct offers, withdrawals and loaded-reference provenance. */
    private class SourceState(tools: List<ToolDefinition>) {
        private val known = tools.associateByTo(linkedMapOf()) { it.name }
        private val initial = initiallyAvailable(tools)
        private val current = initial.mapTo(linkedSetOf()) { it.name }
        private val withdrawn = linkedSetOf<String>()
        private val loaded = mutableListOf<LoadedDefinition>()
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
                    offer(tool)
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
                withdrawn.add(tool.name)
            }
        }

        private fun offer(tool: ToolDefinition) {
            current.add(tool.name)
            withdrawn.remove(tool.name)
        }

        private fun loadReferences(block: ToolResultBlock) {
            block.content.filterIsInstance<UnknownBlock>().forEach { reference ->
                val tool = reference.loadedTool?.let(known::get) ?: return@forEach
                loaded.add(LoadedDefinition(reference, tool))
                offer(tool)
            }
        }

        fun availability(): Availability {
            val currentNames = known.values.filter { it.name in current }.map { it.name }
            return Availability(
                initiallyAvailable = initial,
                currentNames = currentNames,
                hasUnrepresentableRemoval = hasUnrepresentableRemoval,
                knownDefinitions = known.values.toList(),
                withdrawnNames = withdrawn.toSet(),
                loadedDefinitions = loaded.toList(),
                currentDefinitions = current.mapNotNull(known::get),
            )
        }
    }
}
