// NEW: V4-390 — the one author of a lite turn's mid-conversation context message, and its recognizer.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars

/**
 * Claude Code sends a peer's message, a task notification and hook output as a role=system message.
 * codex never sends a `system` input item: its mid-conversation context is a developer message
 * (codex-rs core/src/context/hook_additional_context.rs:21), so a lite turn sends it that way
 * (2,579 system items in eight claudex sessions on Sep 28, 2,379 of them right after a tool output).
 * The explicit message type is what keeps it apart from the lite preamble's untyped developer items,
 * so code mode asks [isContext] instead of reading roles itself.
 */
public object ResponsesContextMessage {
    /** The role Claude Code gives these messages. */
    public const val CLIENT_ROLE: String = "system"

    public fun item(text: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("role", "developer")
        put("content", text)
    }

    public fun isContext(item: JsonObject?): Boolean =
        JsonScalars.str(item, "type") == "message" && JsonScalars.str(item, "role") == "developer"
}
