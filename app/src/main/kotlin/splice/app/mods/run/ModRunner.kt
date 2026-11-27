package splice.app.mods.run

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.mods.ModRuntime
import splice.app.mods.ModRuntimeStatus
import splice.app.mods.handover.ModAcceptedBatches
import splice.app.mods.handover.ModHandover
import splice.app.mods.handover.ModHandoverBatch
import splice.app.mods.handover.ModReceipts
import splice.app.mods.install.InstalledMods
import splice.core.config.MgmtKey
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
private const val MOD_TEARDOWN_MS = 9000L // why: a blocked mod must not hold the daemon after retirement

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
    private val acceptedBatches = ModAcceptedBatches()
    private var handover: ModHandover? = null
    private var startup: Job? = null
    var handoverStarts: splice.app.mods.handover.ModHandoverStarts? = null

    override val handoverDropped: Long get() = events.handoverDropped

    private enum class Phase { NEW, RUNNING, STOPPED }
    private var phase = Phase.NEW

    @Synchronized
    fun beginHandover(port: Int, key: MgmtKey) {
        // The fence below ends what this daemon accepts, so the list handed on is final.
        val forwarding = ModHandover(scope, port, key, log, acceptedBatches.snapshot())
        handover = forwarding
        events.handOver(forwarding)
        fence("handover", forwarding)
    }

    fun attachSessions(sessions: ModSessions) {
        calls.sessions = sessions
    }

    fun leave(starts: Collection<splice.app.mods.sessions.ModPendingSession> = emptyList()) {
        handover?.leave(starts)
    }

    suspend fun finishHandover() {
        handover?.finish()
    }

    @Synchronized
    fun acceptHandover(batch: ModHandoverBatch): Boolean {
        if (phase != Phase.RUNNING || handoverStarts?.holds(batch) == true) return false
        val sender = batch.sender
        val last = sender?.let(acceptedBatches::of) ?: -1L
        if (sender != null && batch.ordinal <= last) return true
        if (batch.startsComplete) acceptedBatches.record(ModReceipts(batch.accepted, batch.acceptedAt))
        batch.events.forEach(::receive)
        val confirmed = batch.uncertain?.takeIf { it.ordinal <= last }?.count ?: 0
        events.deliveryLoss.add((batch.dropped - confirmed).coerceAtLeast(0))
        handoverStarts?.accept(batch)
        if (sender != null) acceptedBatches.record(sender, batch.ordinal)
        return true
    }

    private fun receive(event: splice.app.mods.handover.ModDelivery) {
        val target = event.only
        when {
            target != null -> roster[target]?.receive(event) ?: events.deliveryLoss.add()
            roster.isEmpty() -> events.deliveryLoss.add()
            else -> roster.values.forEach { it.receive(event) }
        }
    }

    suspend fun stop(reason: String = "daemon stop") {
        val stopping = fence(reason)
        finishHandover()
        withTimeoutOrNull(MOD_TEARDOWN_MS) { stopping.map(ModSupervisor::job).joinAll() }
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
            prior?.job ?: startup,
            mod,
            setup,
            events,
            output,
            waiter,
        ).also {
            it.arm()
            it.job.start()
        }
    }

    override suspend fun settled(name: String) {
        val supervisor = roster[name] ?: return
        val mod = installed.find(name)
        withTimeoutOrNull(CHANGE_WAIT_MS) {
            if (mod?.enabled == true) supervisor.settled.await() else supervisor.job.join()
        }
        if (mod == null && supervisor.job.isCompleted) roster.remove(name, supervisor)
    }

    /** Arms intake before listeners open. Only the mod jobs wait for the post-bind history and process checks. */
    @Synchronized
    fun start(sessions: ModSessions? = null, recoveredSessions: Job? = null) {
        if (phase != Phase.NEW) return
        calls.sessions = sessions
        startup = recoveredSessions
        installed.runtime = this
        installed.unreadable?.let { log("[mods] $it\n") }
        phase = Phase.RUNNING
        installed.all().filter { it.enabled }.forEach { changed(it.name) }
    }

    @Synchronized
    private fun fence(reason: String, forwarding: ModHandover? = null): List<ModSupervisor> {
        phase = Phase.STOPPED
        return roster.values.toList().onEach { it.stop(reason, forwarding) }
    }
}
