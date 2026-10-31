// NEW: v0.4.0 spec section 11 ("Per-session and per-head pinning select the intended account under concurrent
// sessions"). The pool's manual pins, held apart from AccountPool so that class stays under detekt's function
// ceiling. A session pin outranks the head pin for THAT session only; a pin for one session never reaches another.
package splice.upstream.credentials

/** Session pins are bounded like the pool's own sticky sessions: the oldest goes first, so a client minting a new
 *  session id per run cannot grow this without limit. */
private const val MAX_SESSION_PINS = 512

internal class PinBook {
    private var head: String? = null
    private val sessions = LinkedHashMap<String, String>()

    @Synchronized
    fun pin(label: String, sessionId: String?) {
        if (sessionId == null) {
            head = label
            return
        }
        sessions.remove(sessionId)
        sessions[sessionId] = label
        if (sessions.size > MAX_SESSION_PINS) sessions.remove(sessions.keys.first())
    }

    /** Drops one session's pin, or the head-wide pin when [sessionId] is null. */
    @Synchronized
    fun unpin(sessionId: String?) {
        if (sessionId == null) head = null else sessions.remove(sessionId)
    }

    /** What [sessionId] is pinned to: its own pin, else the head's. A null session sees the head's only. */
    @Synchronized
    fun forSession(sessionId: String?): String? = sessionId?.let(sessions::get) ?: head

    /** The pin this session set for itself, ignoring the head's. */
    @Synchronized
    fun ownedBy(sessionId: String?): String? = sessionId?.let(sessions::get)

    @Synchronized
    fun clear() {
        head = null
        sessions.clear()
    }
}
