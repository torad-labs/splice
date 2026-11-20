// NEW: where a mode keeps what it needs per session (modes/README.md, "The contract"). splice backs it with files
// under its state folder, so what a mode wrote is there after a restart.
package splice.modes.api

/** One mode's text per session, by the session's id. Each mode has a store of its own. */
public interface ModeStore {
    /** What this mode last wrote for [session], or null when it wrote nothing. */
    public fun read(session: String): String?

    /** Keeps [text] for [session], in place of what was there; it is on disk when this returns. */
    public fun write(session: String, text: String)
}
