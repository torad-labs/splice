package splice.app.mods.sessions

import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.launch.LaunchNotice
import splice.launch.LaunchNotices
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A turn looks up its own owner. A session-wide read is for the row, not for turn delivery. */
internal interface ModLaunchLookup {
    fun forSession(sessionId: String): LaunchNotice?

    /** Joins the session to a known launch. Null never authorizes using a different launch's settings. */
    fun forTurn(sessionId: String?, owner: String?, served: Boolean = true): LaunchNotice?
}

internal interface InstalledModNames {
    fun names(): Set<String>
}

internal class ModLaunches(
    modsDir: Path,
    private val log: LogSink,
    private val installed: InstalledModNames,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val sessionEvents: ModSessionTracker? = null,
) : LaunchNotices, ModLaunchLookup {
    private val files = ModLaunchFiles(modsDir)
    private val launches = ConcurrentHashMap<String, StoredModLaunch>()
    private val pids = ConcurrentHashMap<Long, StoredModLaunch>()
    private val sessions = ConcurrentHashMap<String, SessionLaunches>()
    private val ordering = AtomicLong(Long.MIN_VALUE)
    private val lastPrune = AtomicLong(Long.MIN_VALUE)

    @Volatile private var accepting = true

    /** After the daemon owns its state, before serving routes. */
    fun restore() {
        val restored = Cancellables.runCatchingCancellable(files::read).getOrElse {
            log("[mods] could not read previous launch settings\n")
            emptyList()
        }
        if (!accepting) return
        restored.forEach(::remember)
        ordering.accumulateAndGet(restored.maxOfOrNull { it.recordedAt } ?: Long.MIN_VALUE, ::maxOf)
        queuePrune()
    }

    @Synchronized
    fun stop() {
        accepting = false
    }

    @Synchronized
    override fun launched(launch: LaunchNotice) {
        if (!accepting) return
        val at = clock()
        val order = ordering.updateAndGet { previous -> maxOf(at, previous + 1) }
        val record = StoredModLaunch(launch, order, launch.pid?.let(ModLaunchProcess::birth))
        remember(record)
        val queued = AsyncFileIo.submitFor(files.path(record)) { files.write(record) }
        if (!queued) log("[mods] could not queue launch settings for restart\n")
        queuePrune()
    }

    override fun unknownMods(names: Set<String>): Set<String> =
        if (names.isEmpty()) names else names - installed.names()

    override fun sessionStarted(owner: String?, sessionId: String, cwd: String) {
        forTurn(sessionId, owner, served = false)
        sessionEvents?.see(ModSessionSighting(sessionId, owner, clock(), cwd), this)
    }

    fun stored(owner: String): StoredModLaunch? = launches[owner]

    override fun forSession(sessionId: String): LaunchNotice? = sessions[sessionId]?.latest?.launch

    /** A hook can supply labels before a turn, but only admission proves a launch served this id. */
    override fun forTurn(sessionId: String?, owner: String?, served: Boolean): LaunchNotice? {
        val record = owner?.let(launches::get) ?: return null
        if (sessionId != null && sessions[sessionId]?.includes(record, served) != true) {
            sessions.compute(sessionId) { _, joined ->
                // A turn racing with pruning must not put the removed owner back in the index.
                if (launches.containsKey(record.launch.owner)) {
                    (joined ?: SessionLaunches()).join(record, served)
                } else {
                    joined
                }
            }
        }
        return record.launch
    }

    /** The serving launches still retained, not hook-only associations or unowned turns. */
    fun servingLaunches(): Map<String, List<StoredModLaunch>> = buildMap {
        for ((id, joined) in sessions) {
            if (joined.served.isEmpty()) continue
            val retained = joined.served.mapNotNull(launches::get)
            if (retained.isNotEmpty()) put(id, retained)
        }
    }

    /** Registered rows can also be read before their hook arrives, or after the daemon reopens. */
    fun forRecord(sessionId: String?, pid: Long?): LaunchNotice? {
        if (pid != null) return pids[pid]?.takeIf(StoredModLaunch::isAlive)?.launch
        return sessionId?.let(sessions::get)?.latest?.launch
    }

    private fun remember(record: StoredModLaunch) {
        launches[record.launch.owner] = record
        record.launch.pid?.let { pids.merge(it, record, StoredModLaunch::newest) }
    }

    private fun queuePrune() {
        val now = clock()
        val previous = lastPrune.get()
        if (previous > now - TimeUnit.MINUTES.toMillis(1) || !lastPrune.compareAndSet(previous, now)) return
        val _ = AsyncFileIo.submit { prune(now) }
    }

    private fun prune(now: Long) {
        for (record in launches.values) {
            val gone = record.goneAt
            if (gone == null && !record.isAlive()) {
                val ended = record.copy(goneAt = now)
                files.write(ended)
                launches.replace(record.launch.owner, record, ended)
            }
            if (gone != null && gone <= now - TimeUnit.DAYS.toMillis(1)) remove(record)
        }
    }

    private fun remove(record: StoredModLaunch) {
        Files.deleteIfExists(files.path(record))
        launches.remove(record.launch.owner, record)
        val owner = record.launch.owner
        record.launch.pid?.let { pid ->
            pids.computeIfPresent(pid) { _, value -> value.takeUnless { it.launch.owner == owner } }
        }
        for ((id, joined) in sessions) {
            if (!joined.owns(owner)) continue
            sessions.computeIfPresent(id) { _, current -> current.without(owner) }
        }
    }
}

private data class SessionLaunches(val latest: StoredModLaunch? = null, val served: Set<String> = emptySet()) {
    fun includes(record: StoredModLaunch, served: Boolean): Boolean =
        (latest?.recordedAt ?: Long.MIN_VALUE) >= record.recordedAt && (!served || record.launch.owner in this.served)

    fun join(record: StoredModLaunch, served: Boolean): SessionLaunches = SessionLaunches(
        latest?.newest(record) ?: record,
        if (served) this.served + record.launch.owner else this.served,
    )

    fun owns(owner: String): Boolean = latest?.launch?.owner == owner || owner in served

    fun without(owner: String): SessionLaunches? = SessionLaunches(
        latest?.takeUnless { it.launch.owner == owner },
        served - owner,
    ).takeIf { it.latest != null || it.served.isNotEmpty() }
}
