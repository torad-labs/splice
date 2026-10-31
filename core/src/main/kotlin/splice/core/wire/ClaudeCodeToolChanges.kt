// NEW: Claude Code's mid-conversation tool-change blocks, declared once in :core for the dialects that read them
// (2026-10-27). Claude Code adds a tool with a `tool_addition` in a system message, removes one with a `tool_removal`,
// and loads a deferred one by answering ToolSearch with a `tool_reference`. The Anthropic passthrough reads them to
// keep the tool list of a head whose upstream refuses the blocks, and the Chat dialect reads them to build its tool
// list. One declaration makes the two readers one vocabulary.
package splice.core.wire

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

public object ClaudeCodeToolChanges {
    /** The role of the messages that carry the tool changes. */
    public const val CHANGE_ROLE: String = "system"

    public const val TOOL_ADDITION: String = "tool_addition"
    public const val TOOL_REMOVAL: String = "tool_removal"
    public const val TOOL_REFERENCE: String = "tool_reference"
    public const val TOOL_DEFINITION: String = "tool_definition"

    /** The field of a change block that holds the tool it names. */
    public const val FIELD_TOOL: String = "tool"

    /** The field of a `tool_definition` that holds the definition. */
    public const val FIELD_DEFINITION: String = "definition"

    /** Whether [message], a message of the conversation as the client sent it, is from the role that carries tool
     *  changes. */
    public fun carriesChanges(message: JsonObject): Boolean = JsonScalars.str(message, "role") == CHANGE_ROLE
}
