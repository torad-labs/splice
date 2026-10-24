// NEW: Oct 15, 2026 (the splice manager's composer) — what each session's status line last said, as figures.
//
// A Claude Code session pipes its state to the status-line command on every tick, and the command's post to
// /statusline/{head} is the one place splice sees the session's model, its reasoning effort and how full its
// context is: no request carries them, and the daemon kept none of it. The console's composer draws the same facts
// (context used, the model, the effort) without running a status line, so the route that renders each post also
// records them here, and the manager's state reads them back by session id.
//
// MEMORY ONLY. It is a view of the last tick, never a source of truth: a session keeps posting, a post always wins,
// and a daemon restart starts empty until the next tick (a few seconds).
package splice.core.session

import splice.core.util.LruSizing
import splice.core.util.WallClock

// why: bound retained status-line snapshots as sessions come and go over the daemon's lifetime.
private const val SESSION_LINE_CAPACITY = 256

/** How full a session's context is, in the picked row's own units (the status line's `used/window · percent`).
 *  [cachePercent] is the share of the input that came from the cache, null when nothing was read yet. */
public data class SessionContext(
    val usedTokens: Long,
    val windowTokens: Long,
    val percent: Int,
    val cachePercent: Int?,
)

/** One tick of a session's status line. [model] is the name the line shows and [modelId] the id the client sent;
 *  [context] is null before the first turn, or when the usage the tick carries is another head's. [atMs] is when
 *  splice read the tick, stamped by [SessionLines]. */
public data class SessionLine(
    val modelId: String?,
    val model: String?,
    val effort: String?,
    val context: SessionContext?,
    val atMs: Long = 0,
)

/** Session id -> its last [SessionLine]. Bounded (least-recently-touched eviction) since sessions come and go for the
 *  daemon's whole life. */
public class SessionLines(
    private val capacity: Int = SESSION_LINE_CAPACITY,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val lock = Any()
    private val lines =
        object : LinkedHashMap<String, SessionLine>(LruSizing.INITIAL_CAPACITY, LruSizing.LOAD_FACTOR, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, SessionLine>?,
            ): Boolean = size > capacity
        }

    /** Keeps [line] as the session's latest, stamped now. A post that names no session is nobody's. */
    public fun record(sessionId: String?, line: SessionLine) {
        if (sessionId.isNullOrEmpty()) return
        synchronized(lock) { lines[sessionId] = line.copy(atMs = clock()) }
    }

    /** The session's last tick, or null for a session that has not posted a status line since the daemon started. */
    public fun of(sessionId: String?): SessionLine? = sessionId?.let { synchronized(lock) { lines[it] } }
}
