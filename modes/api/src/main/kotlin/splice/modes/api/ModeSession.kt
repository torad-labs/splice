// NEW: the session a mode's call is for (modes/README.md, "The contract").
package splice.modes.api

/** The session: [id] is the id `/api/sessions` gives it, [folder] its working folder when splice knows it, [head]
 *  the head it runs on, [model] the model its request names, and [caller] who is asking. */
public data class ModeSession(
    val id: String,
    val folder: String?,
    val head: String,
    val model: String,
    val caller: ModeCaller,
)

/** Who sends a session's requests. [wire] is the word a manifest's `[callers] off` and `GET /api/modes` use. */
public enum class ModeCaller(public val wire: String) {
    /** Claude Code on splice's door: plain `claude`, here or through another machine's door. */
    DOOR("door"),

    /** A head's own command, e.g. `claude-astra`. */
    HEAD("head"),

    /** Claude Desktop's own sessions, whose requests carry Desktop's key. */
    DESKTOP("desktop"),
}
