// NEW: Claude Code's mid-conversation tool changes, carried by the Chat Completions tool list (2026-10-27).
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.wire.AnthropicRequest
import splice.core.wire.ClaudeCodeToolChanges.CHANGE_ROLE
import splice.core.wire.ClaudeCodeToolChanges.FIELD_DEFINITION
import splice.core.wire.ClaudeCodeToolChanges.FIELD_TOOL
import splice.core.wire.ClaudeCodeToolChanges.TOOL_ADDITION
import splice.core.wire.ClaudeCodeToolChanges.TOOL_DEFINITION
import splice.core.wire.ClaudeCodeToolChanges.TOOL_REFERENCE
import splice.core.wire.ClaudeCodeToolChanges.TOOL_REMOVAL
import splice.core.wire.ContentBlock
import splice.core.wire.FIELD_TYPE
import splice.core.wire.ToolDefinition
import splice.core.wire.ToolResultBlock
import splice.core.wire.UnknownBlock

/**
 * Claude Code adds a tool mid-conversation with a `tool_addition` block in a system message and removes one with a
 * `tool_removal`. No Chat Completions message carries such a block, so the request's tool list carries each change:
 * the client's tools in their order, each with the definition the conversation last gave it, then the tools the
 * conversation added, less the ones it removed. A removed tool comes back when an addition or a ToolSearch reference
 * names it again. A conversation that changes no tool lists the client's tools exactly as sent. Chat has no deferred
 * loading, so every tool the client declares stays listed, as it always has on this dialect.
 */
internal class ChatToolChanges {
    /** The tools [body]'s conversation holds at its end. */
    fun current(body: AnthropicRequest): List<ToolDefinition> {
        val walk = Walk(body.tools)
        body.messages.forEach { message -> message.content.forEach { block -> walk.accept(message.role, block) } }
        return walk.tools() ?: body.tools
    }

    /** Whether [block], in a message from [role], is a tool change the tool list carries in its place, so the message
     *  leaves it out instead of saying it was omitted. */
    fun carried(role: String, block: UnknownBlock): Boolean = role == CHANGE_ROLE && changedName(block) != null

    /** The tool a change block names: an addition's definition or reference, or a removal's reference. */
    private fun changedName(block: UnknownBlock): String? {
        if (block.type != TOOL_ADDITION && block.type != TOOL_REMOVAL) return null
        val tool = block.raw[FIELD_TOOL] as? JsonObject ?: return null
        return when (JsonScalars.str(tool, FIELD_TYPE)) {
            TOOL_DEFINITION -> JsonScalars.str(tool[FIELD_DEFINITION] as? JsonObject, NAME)
            TOOL_REFERENCE -> JsonScalars.str(tool, NAME)
            else -> null
        }
    }

    /** One walk through the conversation in order, so a later change wins over an earlier one. */
    private inner class Walk(declared: List<ToolDefinition>) {
        private val definitions = declared.associateByTo(linkedMapOf()) { it.name }
        private val listed = declared.mapTo(linkedSetOf()) { it.name }
        private val added = linkedSetOf<String>()
        private val withdrawn = mutableSetOf<String>()
        private var changed = false

        fun accept(role: String, block: ContentBlock) {
            if (block is ToolResultBlock) {
                block.content.filterIsInstance<UnknownBlock>().mapNotNull { it.loadedTool }.forEach(::offer)
            }
            if (block is UnknownBlock && carried(role, block)) change(block)
        }

        /** The list, or null when nothing in the conversation changed it. */
        fun tools(): List<ToolDefinition>? =
            if (changed) (listed + added).filterNot { it in withdrawn }.map(definitions::getValue) else null

        private fun change(block: UnknownBlock) {
            val name = changedName(block) ?: return
            val definition = (block.raw[FIELD_TOOL] as? JsonObject)?.get(FIELD_DEFINITION) as? JsonObject
            when {
                block.type == TOOL_REMOVAL -> if (name in definitions && withdrawn.add(name)) changed = true
                definition != null -> define(name, definition)
                else -> offer(name)
            }
        }

        private fun define(name: String, source: JsonObject) {
            definitions[name] = ToolDefinition(
                name = name,
                description = JsonScalars.str(source, "description"),
                inputSchema = source["input_schema"] as? JsonObject,
            )
            if (name !in listed) added.add(name)
            withdrawn.remove(name)
            changed = true
        }

        private fun offer(name: String) {
            if (withdrawn.remove(name)) changed = true
        }
    }
}
