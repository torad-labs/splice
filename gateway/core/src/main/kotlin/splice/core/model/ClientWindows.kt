// NEW: the context window each Claude Code SESSION actually runs with (2026-09-05) — learned from
// its status-line posts, so a TOML window edit reaches a running process through ITS window.
//
// Claude Code fixes its window per PROCESS from the launch env (the pinned row's window at launch
// time), and splice scales the token counts it reports by client/declared so a row compacts at its
// own declared window. A session launched before a TOML window edit still holds the old value for
// its whole life, and scaling its counts against the wrong window compacts it at the wrong point —
// at a THIRD of its row's window when the assumed window was a constant 1e6 (operator report
// 2026-09-05: "claudex sessions spend more time compacting than doing anything else"). The client
// tells us its window on every status-line post (`session_id` + `context_window.context_window_size`),
// so the proxy learns it per session and scales THAT session's counts against it — live, no relaunch.
package splice.core.model

private const val DEFAULT_CAPACITY = 512

/** Session id -> the window Claude Code computed for an env-governed id in that process. Bounded
 *  (least-recently-touched eviction) since sessions come and go for the daemon's whole life. */
public class ClientWindows(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lock = Any()
    private val windows = object : LinkedHashMap<String, Long>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > capacity
    }

    public fun record(sessionId: String?, window: Long?) {
        if (sessionId.isNullOrEmpty()) return
        val positive = window?.takeIf { it > 0 } ?: return
        synchronized(lock) { windows[sessionId] = positive }
    }

    /** The session's window, or null for a session that has not posted a status line yet. */
    public fun windowFor(sessionId: String?): Long? =
        sessionId?.let { synchronized(lock) { windows[it] } }
}

private const val INITIAL_CAPACITY = 16
private const val LOAD_FACTOR = 0.75f
