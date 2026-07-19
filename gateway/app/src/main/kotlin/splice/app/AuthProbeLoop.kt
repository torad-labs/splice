// NEW: pre-traffic auth/health probe (G8). Mirrors the delay-loop idiom in
// provider-spi/src/main/kotlin/splice/spi/Watchdog.kt:47-52 (scope.launch { while (isActive) { ... } }).
// A cheap per-head background check: read credentials() (cached/local — no new network for
// api-key heads, since RefreshableAuthProvider.refresh() == credentials() there), and on a null
// result explicitly call refresh() — the existing SingleFlight-protected path every real turn
// already goes through (GrokAuthProvider.kt/KimiAuthProvider.kt/CodexAuthProvider.kt all route
// doRefresh() through their own singleFlight.run{}), so a probe-triggered refresh can never race
// a request-triggered one. Logs ONLY on healthy<->unhealthy transitions, plus an immediate log if
// the very first tick is already unhealthy (the pre-traffic catch).
package splice.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splice.core.auth.AuthProvider
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.runCatchingCancellable

/**
 * Daemon wiring helper (top-level, not a Daemon member, to keep Daemon's own function count
 * under detekt's TooManyFunctions): cast + start, no-op for a non-refreshable [AuthProvider] —
 * currently always succeeds (every impl is RefreshableAuthProvider), defensive for a future
 * non-refreshable provider, not dead code. Stores the started loop into [probes] under [key] so
 * the caller can stop() it later.
 */
public fun startAuthProbeIfRefreshable(
    key: String,
    auth: AuthProvider,
    scope: CoroutineScope,
    log: (String) -> Unit,
    probes: MutableMap<String, AuthProbeLoop>,
) {
    val refreshable = auth as? RefreshableAuthProvider ?: return
    val probe = AuthProbeLoop(key, refreshable, log = log)
    probe.start(scope)
    probes[key] = probe
}

public class AuthProbeLoop(
    private val key: String,
    private val auth: RefreshableAuthProvider,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val log: (String) -> Unit,
) {
    @Volatile private var job: Job? = null

    @Volatile private var healthy: Boolean? = null // null = not yet probed

    /** First tick runs immediately (pre-traffic: catches a dead-on-boot auth state before any
     *  real user turn); subsequent ticks wait [intervalMs]. */
    public fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                runCatchingCancellable { probeOnce() }
                    .onFailure { log("[auth-probe:$key] probe tick threw: $it\n") }
                delay(intervalMs)
            }
        }
    }

    public fun stop() {
        job?.cancel()
        job = null
    }

    internal suspend fun probeOnce() {
        var ok = auth.credentials() != null
        if (!ok) ok = auth.refresh() != null // explicit trigger — matters most for codex, which never self-refreshes
        val prev = healthy
        healthy = ok
        when {
            prev == null && !ok -> log("[auth-probe:$key] initial health check: unhealthy\n")
            prev != null && prev != ok -> log("[auth-probe:$key] health ${state(prev)} -> ${state(ok)}\n")
        }
    }

    private fun state(v: Boolean) = if (v) "healthy" else "unhealthy"

    private companion object {
        const val DEFAULT_INTERVAL_MS = 60_000L
    }
}
