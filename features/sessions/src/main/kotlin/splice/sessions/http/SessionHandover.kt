// NEW: Oct 11, 2026 — moved out of SessionContinue.kt (concentration): the waits a move makes, read off the
// registry by RegistryHandover.
package splice.sessions.http

/** Whether a moved session's turn stopped, its client went, and it came back, read off the registry. */
public interface SessionHandover {
    /** Whether process [pid] no longer runs [session] within [seconds]. */
    public suspend fun left(session: String, pid: Long, seconds: Long): Boolean

    /** Whether [session] has no turn running within [seconds]. */
    public suspend fun stopped(session: String, seconds: Long): Boolean

    /** Whether [session] runs on [head] within [seconds]. */
    public suspend fun cameOn(session: String, head: String, seconds: Long): Boolean
}
