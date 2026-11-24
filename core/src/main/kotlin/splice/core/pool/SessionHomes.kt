// NEW: server mode (docs/specs/server-mode.md, F11 session affinity). Which server of a pool served each Claude Code
// session's last turn, so the session's next turn goes back there while it is healthy: that server holds the
// session's prompt in its cache, and any other would prefill the whole transcript cold. Kept per pool and only in
// memory: a daemon restart forgets it, and a session's next turn is balanced like a new one's.
package splice.core.pool

import splice.core.util.LruSizing

/** The server each recent session was last served by, at most [HOMES_KEPT] of them, the least recently used going
 *  first. Read and changed only under its pool's lock. */
internal class SessionHomes {
    private val homes = object : LinkedHashMap<String, String>(
        LruSizing.INITIAL_CAPACITY,
        LruSizing.LOAD_FACTOR,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > HOMES_KEPT
    }

    /** The server that served [session]'s last turn, or null for a session not seen (or no longer kept), or none. */
    fun of(session: String?): String? = session?.let(homes::get)

    /** [server] served [session]'s turn: its next turn goes there while it is healthy. */
    fun served(session: String, server: String) {
        homes[session] = server
    }
}

// why: a session not heard from in this many newer sessions has long left every server's prompt cache (a
// llama-server keeps a handful of slots), so forgetting it costs nothing; ten thousand names of ~40 bytes keep the map
// under a megabyte however long the daemon runs.
private const val HOMES_KEPT = 10_000
