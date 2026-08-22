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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.spi.ProcessTicker
import splice.spi.Ticker

public class AuthProbeLoop(
    private val key: String,
    private val auth: RefreshableAuthProvider,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val log: LogSink,
    // SH-04: wall clock for the restart-budget window only (never tick scheduling) — injectable
    // so the budget tests need no real waiting.
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    // HD-19: the tick cadence, as a NAMED port instead of a bare delay. ProcessTicker is
    // `delay(intervalMs); true`, so production paces exactly as before; a test wires a ticker that
    // returns instantly for N ticks and then false, which ends the loop through the SAME clean-
    // completion path a cancellation takes (cause == null => benign => no restart) rather than
    // throwing, which would be indistinguishable from the loop death this class supervises.
    private val ticker: Ticker = ProcessTicker(),
) {
    @Volatile private var job: Job? = null

    @Volatile private var healthy: Boolean? = null // null = not yet probed

    // SH-04 supervisor state: stop() must win over a racing restart, and the budget is a rolling
    // window (systemd StartLimitBurst shape: MAX_RESTARTS per RESTART_WINDOW_MS).
    @Volatile private var stopped = false
    private val restartTimes = ArrayDeque<Long>()

    /** First tick runs immediately (pre-traffic: catches a dead-on-boot auth state before any
     *  real user turn); subsequent ticks wait [intervalMs]. */
    public fun start(scope: CoroutineScope) {
        if (job != null) return
        stopped = false
        launchSupervised(scope)
    }

    private fun launchSupervised(scope: CoroutineScope) {
        val launched = scope.launch {
            while (isActive) {
                Cancellables.runCatchingCancellable { probeOnce() }
                    .onFailure { log("[$key][auth-probe] probe tick threw: $it\n") }
                if (!ticker.awaitTick(intervalMs)) return@launch
            }
        }
        job = launched
        // SH-04: runCatchingCancellable guards only the KNOWN transient classes; any other
        // throwable out of provider code (IllegalStateException from a check/error, an NPE, a
        // ktor engine type) kills the coroutine — previously silently, with start() refusing to
        // re-arm (job != null), leaving the head unprobed for the daemon's lifetime. The repo's
        // own walls forbid a broad tick catch (ForbiddenSuppress on TooGenericExceptionCaught),
        // so supervision is the layer that owns the unknown-throwable class: restart under a
        // bounded budget, announce exhaustion for SH-08 to surface.
        launched.invokeOnCompletion { cause ->
            val benign = cause == null || cause is kotlinx.coroutines.CancellationException
            if (benign || stopped) {
                return@invokeOnCompletion
            }
            val n = recordRestart()
            if (n <= MAX_RESTARTS) {
                log("[$key][auth-probe] loop died: $cause — restarting ($n/$MAX_RESTARTS)\n")
                launchSupervised(scope)
            } else {
                log(
                    "[$key][auth-probe] loop died: $cause — restart budget exhausted " +
                        "($MAX_RESTARTS in ${RESTART_WINDOW_MS / MS_PER_MIN}m); probe permanently down\n",
                )
            }
        }
    }

    /** Rolling-window restart count including this one; synchronized — completion handlers run
     *  on arbitrary threads. */
    private fun recordRestart(): Int = synchronized(restartTimes) {
        val now = clock()
        while (restartTimes.isNotEmpty() && now - restartTimes.first() > RESTART_WINDOW_MS) {
            restartTimes.removeFirst()
        }
        restartTimes.addLast(now)
        restartTimes.size
    }

    public fun stop() {
        stopped = true
        job?.cancel()
        job = null
    }

    internal suspend fun probeOnce() {
        var ok = auth.credentials() != null
        if (!ok) ok = auth.refresh() != null // explicit trigger — matters most for codex, which never self-refreshes
        val prev = healthy
        healthy = ok
        when {
            prev == null && !ok -> log("[$key][auth-probe] initial health check: unhealthy\n")
            prev != null && prev != ok -> log("[$key][auth-probe] health ${state(prev)} -> ${state(ok)}\n")
        }
    }

    private fun state(v: Boolean) = if (v) "healthy" else "unhealthy"
}

// AuthProbeLoop's tick cadence and SH-04 restart budget (systemd StartLimitBurst shape): sustained
// slow deaths keep restarting forever (the window rolls); only a hot death loop exhausts it, and
// that exhaustion is ANNOUNCED, never silent. File-scope declarations (Kotlin style law,
// 2026-08-15): a top-level `private const val` is the sanctioned home for constants, never a
// static block on the type.
private const val DEFAULT_INTERVAL_MS = 60_000L
private const val MAX_RESTARTS = 5
private const val RESTART_WINDOW_MS = 600_000L
private const val MS_PER_MIN = 60_000L
