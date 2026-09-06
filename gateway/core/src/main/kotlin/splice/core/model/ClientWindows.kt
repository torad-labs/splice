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
//
// PERSISTED (2026-09-05, afternoon): memory-only, every daemon restart forgot every session, and each
// one rode its first turn after the restart on the launch env's window instead of its own (the
// "one raw turn after a restart" residual, measured on 5 of 8 sessions that afternoon). The file
// is a warm start, not a source of truth: a session keeps posting, and a post always wins over it.
package splice.core.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val DEFAULT_CAPACITY = 512

/** Session id -> the window Claude Code computed for an env-governed id in that process. Bounded
 *  (least-recently-touched eviction) since sessions come and go for the daemon's whole life. */
public class ClientWindows(
    private val capacity: Int = DEFAULT_CAPACITY,
    /** Where the registry survives a daemon restart; null (tests) keeps it in memory only. */
    private val store: Path? = null,
    private val log: LogSink = LogSink {},
) {

    private val lock = Any()
    private val ioLock = Any()
    private val windows = object : LinkedHashMap<String, Long>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > capacity
    }

    init {
        store?.let { load(it) }
    }

    public fun record(sessionId: String?, window: Long?) {
        if (sessionId.isNullOrEmpty()) return
        val positive = window?.takeIf { it > 0 } ?: return
        val changed = synchronized(lock) { windows.put(sessionId, positive) != positive }
        // Only a CHANGED value touches the disk: a session posts every few seconds and its window
        // changes about never, so this is one write per session, not one per post.
        if (changed) store?.let { persist(it) }
    }

    /** The session's window, or null for a session that has not posted a status line yet. */
    public fun windowFor(sessionId: String?): Long? =
        sessionId?.let { synchronized(lock) { windows[it] } }

    private fun load(path: Path) {
        if (!Files.exists(path)) return
        try {
            val entries = Json.parseToJsonElement(Files.readString(path)).jsonObject
            synchronized(lock) {
                entries.forEach { (id, value) ->
                    value.jsonPrimitive.longOrNull?.takeIf { it > 0 }?.let { windows[id] = it }
                }
            }
        } catch (e: IOException) {
            log(unreadable(path, e))
        } catch (e: SerializationException) {
            log(unreadable(path, e))
        } catch (e: IllegalArgumentException) {
            // A well-formed JSON document of the wrong shape (an array, a nested object).
            log(unreadable(path, e))
        }
    }

    // Least-recently-touched first, the map's own iteration order, so a reload keeps the eviction
    // order. tmp + ATOMIC_MOVE: a concurrent reader (the next daemon) never sees a torn file.
    private fun persist(path: Path) = synchronized(ioLock) {
        val snapshot = synchronized(lock) { windows.toMap() }
        val json = buildJsonObject { snapshot.forEach { (id, window) -> put(id, window) } }.toString()
        try {
            path.parent?.let { Files.createDirectories(it) }
            val tmp = path.resolveSibling("${path.fileName}.tmp")
            Files.writeString(tmp, json)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            log("[windows] client-window registry not saved to $path (${e::class.simpleName})\n")
        }
    }

    private fun unreadable(path: Path, e: Exception): String =
        "[windows] client-window registry at $path ignored (${e::class.simpleName}); sessions re-teach it\n"
}

private const val INITIAL_CAPACITY = 16
private const val LOAD_FACTOR = 0.75f
