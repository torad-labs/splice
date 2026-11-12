// NEW: Oct 10, 2026 — the terminal a session's own launch recorded, by session id (split from SessionDrive).
package splice.sessions.http

/** The terminal a live session's own launch recorded, and the process that launch became. */
public data class LaunchedTerminal(val pid: Long, val pane: String, val server: String)

/** Where a session started from the person's own terminal runs: null for a session no launch recorded a terminal for,
 *  or one that is no longer running. Read on every act, because the person can close or reuse that terminal. */
public fun interface LaunchedTerminals {
    public fun of(sessionId: String): LaunchedTerminal?
}
