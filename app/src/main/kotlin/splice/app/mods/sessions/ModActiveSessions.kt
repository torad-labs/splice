package splice.app.mods.sessions

import java.util.concurrent.ConcurrentHashMap

/** The birth-checked live pairs and their latest labels, separate from historical ids. */
internal class ModActiveSessions {
    private val processes = ConcurrentHashMap<Long, MutableMap<ModSessionPair, SessionProcess>>()
    private val live = ConcurrentHashMap<ModSessionPair, Live>()

    fun track(pending: ModPendingSession, launch: StoredModLaunch?) {
        val pid = launch?.launch?.pid ?: return
        val birth = launch.processStart ?: return
        val process = SessionProcess(birth, pending)
        processes.compute(pid) { _, entries ->
            live[pending.pair] = Live(pending, launch.recordedAt)
            (entries ?: mutableMapOf()).apply { put(pending.pair, process) }
        }
    }

    fun rows(): Map<String, ModLiveSession> {
        val rows = mutableMapOf<String, ModLiveSession>()
        for ((key, row) in live) {
            val context = row.pending.entry?.context ?: continue
            val candidate = ModLiveSession(context.labels, context.off, row.launchedAt)
            rows.merge(key.id, candidate) { old, new -> if (old.launchedAt < new.launchedAt) new else old }
        }
        return rows
    }

    /** Release the pid key before the caller takes the session key to publish an end. */
    fun expired(): List<ModPendingSession> = buildList {
        for (pid in processes.keys) {
            val birth = ModLaunchProcess.birth(pid)
            processes.computeIfPresent(pid) { _, entries ->
                entries.entries.removeIf { (key, process) ->
                    if (process.birth == birth) return@removeIf false
                    add(process.pending)
                    live.remove(key)
                    true
                }
                entries.takeIf { it.isNotEmpty() }
            }
        }
    }

    private data class Live(val pending: ModPendingSession, val launchedAt: Long)
    private data class SessionProcess(val birth: String, val pending: ModPendingSession)
}
