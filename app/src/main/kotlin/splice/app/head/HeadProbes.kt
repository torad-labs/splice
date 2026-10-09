// PORT-OF: splice/app/Daemon.kt (HeadLifecycle.startDaemonHeads, .startAuthProbeIfRefreshable,
// Daemon's authProbes/turnPathStalled maps, HeadProbeSinks) @ ed5c868 — invariants unchanged: the
// per-head auth/health probe loops. HeadProbeSinks is DELETED, not moved — it existed only to
// shuttle Daemon's two maps under a 6-parameter ceiling, and once the maps live with the loops
// that write them (this class), the parameter object has no reason to exist.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.app.DaemonBoundary
import splice.app.auth.AuthProbeLoop
import splice.app.control.ManagedHead
import splice.app.probe.LocalRuntimeWatch
import splice.app.probe.LocalWindowRefresh
import splice.app.probe.TurnPathProbe
import splice.app.probe.TurnPathProbeLoop
import splice.core.auth.AuthProvider
import splice.core.auth.RefreshableAuthProvider
import splice.core.topology.Topology
import splice.core.util.LogSink
import java.util.concurrent.ConcurrentHashMap

/** What the control plane reads from the daemon's probes, per request: the heads whose turn path stalled, and the
 *  local runtimes that did not answer at the last background probe. [HeadProbes] is the one implementation. */
internal interface HeadProbeReadings {
    fun stalledKeys(): List<String>

    fun runtimeNotAnswering(): Map<String, String>
}

internal class HeadProbes : HeadProbeReadings {

    private val boundary = DaemonBoundary()

    // G8: per-head auth/health probe. Written by the loops this class starts, read by /health via
    // [stalledKeys] and by Daemon.stop() via [stop].
    private val authProbes = LinkedHashMap<String, AuthProbeLoop>()

    private val startGate = Mutex()

    @Volatile
    private var startsOpen = true

    // Turn-path liveness (2026-08-12): key -> stalled. The 91h wedge proved head liveness and head
    // CONFIGURATION are different facts.
    private val turnPathStalled = ConcurrentHashMap<String, Boolean>()

    // V4-417: which local runtimes answer, held by the watch this class starts and read by /health and
    // the heads route through [runtimeNotAnswering]. Null until [startRuntimeWatch] runs.
    private var runtimeWatch: LocalRuntimeWatch? = null

    internal suspend fun startDaemonHeads(
        heads: Map<String, ManagedHead>,
        failed: MutableMap<String, String>,
        probeScope: CoroutineScope,
        log: LogSink,
    ) {
        for ((key, managed) in heads) {
            // One gate for the start and the probes beside it, shared with [closeStarts]: a head is started before the
            // daemon's stop closes the gate, or not at all. A start that began first finishes first, so the stop that
            // follows finds the head it has to stop.
            val admitted = startGate.withLock {
                if (!startsOpen) return@withLock false
                boundary.runCatchingDaemonBoundary { managed.head.start() }.onFailure {
                    failed[key] = "start failed: ${it.message}"
                    log("[$key][boot] failed to start: ${it.message}\n")
                }
                startAuthProbeIfRefreshable(key, managed.auth, probeScope, log)
                TurnPathProbeLoop(key, TurnPathProbe(managed.head.port), turnPathStalled, log).start(probeScope)
                true
            }
            if (!admitted) return
        }
    }

    /** The daemon's stop boundary for head starts, in two steps so a stop cut short by its deadline still holds it. The
     *  fence needs no lock: from the moment it returns, no start that has not begun will begin. Then it waits for a start
     *  already in flight, so the heads the stop stops include every head that was started. A head restart the
     *  operator asks for while the daemon runs does not come through here. */
    internal suspend fun closeStarts() {
        fenceStarts()
        startGate.withLock { }
    }

    /** The fence alone: refuses every start that has not begun, and waits for nothing. */
    internal fun fenceStarts() {
        startsOpen = false
    }

    /** V4-417: starts the background probe of every local head's runtime. Off the request path: nothing
     *  that reads the answer ever asks a runtime. */
    internal fun startRuntimeWatch(
        topology: Topology,
        probeScope: CoroutineScope,
        log: LogSink,
        windows: LocalWindowRefresh,
    ) {
        runtimeWatch = LocalRuntimeWatch(topology, log, windows).also { it.start(probeScope) }
    }

    /**
     * Cast + start, no-op for a non-refreshable [AuthProvider] — currently always succeeds (every
     * impl is RefreshableAuthProvider), defensive for a future non-refreshable provider, not dead
     * code. Stores the started loop into [authProbes] under [key] so [stop] can stop() it later.
     */
    private fun startAuthProbeIfRefreshable(
        key: String,
        auth: AuthProvider,
        scope: CoroutineScope,
        log: LogSink,
    ) {
        val refreshable = auth as? RefreshableAuthProvider ?: return
        val probe = AuthProbeLoop(key, refreshable, log = log)
        probe.start(scope)
        authProbes[key] = probe
    }

    internal fun stop() {
        authProbes.values.forEach { it.stop() }
    }

    override fun stalledKeys(): List<String> = turnPathStalled.filterValues { it }.keys.sorted()

    override fun runtimeNotAnswering(): Map<String, String> = runtimeWatch?.notAnswering().orEmpty()
}
