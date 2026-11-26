package splice.app.mods.run

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.mods.ModRuntime
import splice.app.mods.ModRuntimeStatus
import splice.app.mods.install.InstalledMods
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.LifecycleScope
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessWaiter
import java.util.concurrent.ConcurrentHashMap

// why: a remote install can report starting instead of waiting for a hung load
private const val CHANGE_WAIT_MS = 10_000L

internal class ModRunner(
    private val paths: StatePaths,
    private val installed: InstalledMods,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val io: CoroutineDispatcher = ProcessDispatchers().io(),
    private val waiter: Waiter = ProcessWaiter(),
) : ModRuntimeStatus {
    val events = ModEventHub()
    private val calls = ModCalls()
    private val setup = ModProcessSetup(paths, log, calls, installed.bun, io)
    private val scope = LifecycleScope(io)
    private val roster = ConcurrentHashMap<String, ModSupervisor>()
    private enum class Phase { NEW, REAPING, RUNNING, STOPPED }
    private var phase = Phase.NEW

    /** Called only by the daemon holding the lock, before the management routes accept changes. */
    suspend fun start(sessions: ModSessions? = null) {
        if (!beginStart()) return
        withContext(io) { setup.records.reap() }
        startRoster(sessions)
    }

    @Synchronized
    private fun beginStart(): Boolean {
        if (phase != Phase.NEW) return false
        phase = Phase.REAPING
        return true
    }

    fun beginStop(reason: String) {
        fence(reason)
    }

    suspend fun stop(reason: String = "daemon stop") {
        val stopping = fence(reason)
        stopping.map(ModSupervisor::job).joinAll()
        scope.cancel()
    }

    override fun of(name: String): ModRuntime? = roster[name]?.status()

    @Synchronized
    override fun changed(name: String) {
        if (phase != Phase.RUNNING) return
        val prior = roster[name]
        val mod = installed.find(name)
        prior?.stop(
            when {
                mod == null -> "removed"
                !mod.enabled -> "disabled"
                else -> "restarted"
            },
        )
        if (mod?.enabled != true) return
        val output = prior?.output ?: ModOutput(paths, name, log, clock)
        roster[name] = ModSupervisor(
            scope,
            prior?.job,
            mod,
            setup,
            events,
            output,
            waiter,
        ).also { it.job.start() }
    }

    override suspend fun settled(name: String) {
        val supervisor = roster[name] ?: return
        val mod = installed.find(name)
        withTimeoutOrNull(CHANGE_WAIT_MS) {
            if (mod?.enabled == true) supervisor.settled.await() else supervisor.job.join()
        }
        if (mod == null && supervisor.job.isCompleted) roster.remove(name, supervisor)
    }

    @Synchronized
    private fun startRoster(sessions: ModSessions?) {
        if (phase != Phase.REAPING) return
        calls.sessions = sessions
        installed.runtime = this
        installed.unreadable?.let { log("[mods] $it\n") }
        phase = Phase.RUNNING
        installed.all().filter { it.enabled }.forEach { changed(it.name) }
    }

    @Synchronized
    private fun fence(reason: String): List<ModSupervisor> {
        phase = Phase.STOPPED
        return roster.values.toList().onEach { it.stop(reason) }
    }
}
