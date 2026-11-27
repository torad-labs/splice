package splice.app.mods.sessions

import java.util.concurrent.ConcurrentHashMap

/** The birth-checked live pairs and their latest labels, separate from historical ids. */
internal class ModActiveSessions {
    private val processes = ConcurrentHashMap<Long, MutableMap<ModSessionPair, SessionProcess>>()
    private val live = ConcurrentHashMap<ModSessionPair, ModLiveSession>()

    fun track(entry: ModSessionEntry, launch: StoredModLaunch?) {
        val pid = launch?.launch?.pid ?: return
        val birth = launch.processStart ?: return
        val process = SessionProcess(birth, entry)
        processes.compute(pid) { _, entries ->
            live[entry.pair] = ModLiveSession(entry.context.labels, entry.context.off, launch.recordedAt)
            (entries ?: mutableMapOf()).apply { put(entry.pair, process) }
        }
    }

    fun rows(): Map<String, ModLiveSession> {
        val rows = mutableMapOf<String, ModLiveSession>()
        for ((key, row) in live) {
            rows.merge(key.id, row) { old, new -> if (old.launchedAt < new.launchedAt) new else old }
        }
        return rows
    }

    /** Release the pid key before the caller takes the session key to publish an end. */
    fun expired(at: Long): List<ModSessionEntry> = buildList {
        for (pid in processes.keys) {
            val birth = ModLaunchProcess.birth(pid)
            processes.computeIfPresent(pid) { _, entries ->
                entries.entries.removeIf { (key, process) ->
                    if (process.birth == birth) return@removeIf false
                    add(process.entry.copy(at = at, ended = true))
                    live.remove(key)
                    true
                }
                entries.takeIf { it.isNotEmpty() }
            }
        }
    }

    private data class SessionProcess(val birth: String, val entry: ModSessionEntry)
}
