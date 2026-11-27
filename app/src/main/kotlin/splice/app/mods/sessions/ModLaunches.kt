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
    fun forLaunch(owner: String): LaunchNotice?
    fun forSession(sessionId: String): LaunchNotice?

    /** Joins the session to a known launch. Null never authorizes using a different launch's settings. */
    fun forTurn(sessionId: String?, owner: String?): LaunchNotice?
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
    private val sessions = ConcurrentHashMap<String, StoredModLaunch>()
    private val ordering = AtomicLong(Long.MIN_VALUE)
    private val lastPrune = AtomicLong(Long.MIN_VALUE)
    private var accepting = true

    /** After the daemon owns its state, before serving routes. */
    fun restore() {
        val restored = Cancellables.runCatchingCancellable(files::read).getOrElse {
            log("[mods] could not read previous launch settings\n")
            emptyList()
        }
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
        val launch = forTurn(sessionId, owner)
        sessionEvents?.see(sessionId, launch?.owner?.let(::stored), clock(), cwd)
    }

    fun stored(owner: String): StoredModLaunch? = launches[owner]

    override fun forLaunch(owner: String): LaunchNotice? = launches[owner]?.launch

    override fun forSession(sessionId: String): LaunchNotice? = sessions[sessionId]?.launch

    /** The first admitted turn can bind a headless run without creating a sessions row. */
    override fun forTurn(sessionId: String?, owner: String?): LaunchNotice? {
        val record = owner?.let(launches::get) ?: return null
        if (sessionId != null && record.recordedAt > (sessions[sessionId]?.recordedAt ?: Long.MIN_VALUE)) {
            sessions.merge(sessionId, record, StoredModLaunch::newest)
        }
        return record.launch
    }

    /** Registered rows can also be read before their hook arrives, or after the daemon reopens. */
    fun forRecord(sessionId: String?, pid: Long?): LaunchNotice? {
        val joined = sessionId?.let(sessions::get)
        val registered = pid?.let(pids::get)?.takeIf {
            (joined == null || it.recordedAt > joined.recordedAt) && it.isAlive()
        }
        return (registered ?: joined)?.launch
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
        sessions.entries.removeIf { it.value.launch.owner == owner }
    }
}
