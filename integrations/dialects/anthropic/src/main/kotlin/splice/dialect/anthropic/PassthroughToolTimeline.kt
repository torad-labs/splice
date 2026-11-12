// 2026-10-27: what Claude Code's conversation did to its tool list, read for a head whose upstream refuses the blocks
// that did it. Claude Code adds a tool mid-conversation with a `tool_addition` in a system message, removes one with a
// `tool_removal`, and loads a deferred one by answering ToolSearch with a `tool_reference`. A head's allowlist drops
// each block its upstream refuses, so on that head the tool list carries the change the block made: claude-kimi
// dropped both change blocks at 14:46 CT that day, and the three Claude Docs tools Claude Code added never reached
// Kimi's model. A head whose upstream reads a block keeps it, and its list stays as the client sent it.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.wire.ClaudeCodeToolChanges
import splice.core.wire.ClaudeCodeToolChanges.FIELD_DEFINITION
import splice.core.wire.ClaudeCodeToolChanges.FIELD_TOOL
import splice.core.wire.ClaudeCodeToolChanges.TOOL_ADDITION
import splice.core.wire.ClaudeCodeToolChanges.TOOL_DEFINITION
import splice.core.wire.ClaudeCodeToolChanges.TOOL_REFERENCE
import splice.core.wire.ClaudeCodeToolChanges.TOOL_REMOVAL
import splice.core.wire.UnknownBlock

/** One walk of a conversation in order, for the head whose allowlist is [allowlist]. */
internal class PassthroughToolTimeline(allowlist: Set<String>?) {

    /** The upstream never reads `tool_addition`, so an added tool rides in the list. */
    private val carriesAdditions = allowlist?.let { TOOL_ADDITION !in it } == true

    /** The upstream never reads `tool_removal`, so a removed tool leaves the list. */
    private val carriesRemovals = allowlist?.let { TOOL_REMOVAL !in it } == true

    /** The upstream never reads `tool_reference`, so a loaded tool rides without `defer_loading`. */
    private val carriesLoads = allowlist?.let { TOOL_REFERENCE !in it } == true

    /** The list carries at least one kind of change, so the conversation is worth walking. */
    private val carriesAny = listOf(carriesAdditions, carriesRemovals, carriesLoads).any { it }

    /** What the list must carry by the conversation's end. [definitions] holds the definition each added tool was last
     *  given, [added] the added names the client's list lacks, in the order they arrived, [loaded] the tools that ride
     *  without `defer_loading`, and [withdrawn] the tools that leave the list. */
    data class Changes(
        val definitions: Map<String, JsonObject>,
        val added: List<String>,
        val loaded: Set<String>,
        val withdrawn: Set<String>,
    )

    /** The changes [conversation], the request's `messages`, made to the client's tools named [client]. */
    fun changes(client: Collection<String>, conversation: JsonElement?): Changes {
        val walk = Walk(client.toSet())
        if (carriesAny) {
            (conversation as? JsonArray)?.forEach { message -> (message as? JsonObject)?.let(walk::message) }
        }
        return Changes(walk.definitions, walk.added.toList(), walk.loaded, walk.withdrawn)
    }

    private inner class Walk(private val client: Set<String>) {
        val definitions = linkedMapOf<String, JsonObject>()
        val added = linkedSetOf<String>()
        val loaded = mutableSetOf<String>()
        val withdrawn = mutableSetOf<String>()

        private fun known(name: String): Boolean = name in client || name in definitions

        fun message(message: JsonObject) {
            val system = ClaudeCodeToolChanges.carriesChanges(message)
            (message[CONTENT] as? JsonArray)?.forEach { element -> (element as? JsonObject)?.let { block(system, it) } }
        }

        private fun block(system: Boolean, block: JsonObject) {
            when (JsonScalars.strOrEmpty(block[TYPE])) {
                TOOL_ADDITION -> if (system && carriesAdditions) add(block)
                TOOL_REMOVAL -> if (system && carriesRemovals) remove(block)
                TYPE_TOOL_RESULT -> referenced(block[CONTENT]).forEach(::load)
                else -> UnknownBlock(block).loadedTool?.let(::load)
            }
        }

        /** A definition becomes the tool's own, freshly deferred or not as it says; a reference offers a known tool. */
        private fun add(block: JsonObject) {
            val tool = block[FIELD_TOOL] as? JsonObject ?: return
            when (JsonScalars.str(tool, TYPE)) {
                TOOL_DEFINITION -> {
                    val definition = tool[FIELD_DEFINITION] as? JsonObject ?: return
                    val name = JsonScalars.str(definition, NAME) ?: return
                    definitions[name] = definition
                    if (name !in client) added += name
                    loaded -= name
                    withdrawn -= name
                }
                TOOL_REFERENCE -> JsonScalars.str(tool, NAME)?.takeIf(::known)?.let { name ->
                    loaded += name
                    withdrawn -= name
                }
            }
        }

        private fun remove(block: JsonObject) {
            val name = JsonScalars.str(block[FIELD_TOOL] as? JsonObject, NAME)?.takeIf(::known) ?: return
            loaded -= name
            withdrawn += name
        }

        /** A reference loads its tool, and offers again one the conversation removed. */
        private fun load(name: String) {
            if (carriesLoads) loaded += name
            if (carriesRemovals && known(name)) withdrawn -= name
        }

        /** Every tool a reference in [content] names, a tool_result's nested in it too. A worklist, not recursion: the
         *  client chooses how deep results nest. */
        private fun referenced(content: JsonElement?): List<String> {
            val pending = ArrayDeque<JsonElement?>().apply { add(content) }
            return buildList {
                while (pending.isNotEmpty()) {
                    (pending.removeFirst() as? JsonArray)?.forEach { element ->
                        val block = element as? JsonObject ?: return@forEach
                        UnknownBlock(block).loadedTool?.let { add(it) }
                        if (JsonScalars.strOrEmpty(block[TYPE]) == TYPE_TOOL_RESULT) pending.add(block[CONTENT])
                    }
                }
            }
        }
    }
}
