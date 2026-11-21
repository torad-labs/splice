// NEW: what a mode is given for one call of one of its tools (modes/README.md, "The contract").
package splice.modes.api

import kotlinx.serialization.json.JsonObject

/** One call: the tool's name as the manifest writes it (`say_back`, never the offered `mcp__…` name), its input as
 *  the model wrote it, the session it is for, and the mode's own store. */
public data class ModeCall(
    val tool: String,
    val input: JsonObject,
    val session: ModeSession,
    val store: ModeStore,
)
