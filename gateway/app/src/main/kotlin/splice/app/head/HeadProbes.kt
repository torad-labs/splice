// PORT-OF: splice/app/Daemon.kt (HeadLifecycle.startDaemonHeads, .startAuthProbeIfRefreshable,
// Daemon's authProbes/turnPathStalled maps, HeadProbeSinks) @ ed5c868 — invariants unchanged: the
// per-head auth/health probe loops. HeadProbeSinks is DELETED, not moved — it existed only to
// shuttle Daemon's two maps under a 6-parameter ceiling, and once the maps live with the loops
// that write them (this class), the parameter object has no reason to exist.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import splice.app.AuthProbeLoop
import splice.app.DaemonBoundary
import splice.app.TurnPathProbeLoop
import splice.control.ManagedHead
import splice.core.auth.AuthProvider
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.LogSink
import java.util.concurrent.ConcurrentHashMap

internal class HeadProbes {

    private val boundary = DaemonBoundary()

    // G8: per-head auth/health probe. Written by the loops this class starts, read by /health via
    // [stalledKeys] and by Daemon.stop() via [stop].
    private val authProbes = LinkedHashMap<String, AuthProbeLoop>()

    // Turn-path liveness (2026-08-12): key -> stalled. The 91h wedge proved head liveness and head
    // CONFIGURATION are different facts.
    private val turnPathStalled = ConcurrentHashMap<String, Boolean>()

    internal suspend fun startDaemonHeads(
        heads: Map<String, ManagedHead>,
        failed: MutableMap<String, String>,
        probeScope: CoroutineScope,
        log: LogSink,
    ) {
        heads.forEach { (key, managed) ->
            boundary.runCatchingDaemonBoundary { managed.head.start() }.onFailure {
                failed[key] = "start failed: ${it.message}"
                log("[$key][boot] failed to start: ${it.message}\n")
            }
            startAuthProbeIfRefreshable(key, managed.auth, probeScope, log)
            TurnPathProbeLoop(key, managed.head.port, turnPathStalled, log).start(probeScope)
        }
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

    internal fun stalledKeys(): List<String> = turnPathStalled.filterValues { it }.keys.sorted()
}
