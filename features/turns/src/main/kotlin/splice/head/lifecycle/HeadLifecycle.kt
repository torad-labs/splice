package splice.head.lifecycle

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.head.HeadDeps
import splice.head.HeadEngine
import splice.head.TurnService
import splice.upstream.Provider

// End in-flight SSE turns with an honest terminal before tearing the engine.
//
// The drain's budget is HeadDeps' DEFAULT_STOP_DRAIN_MS, now 0: a restart cuts each running turn at once with the
// head's restart line rather than waiting for it, so the next daemon takes over in seconds.
// The cut is sealed while the client socket is still open, so the client reads the line, never a torn stream. This
// is the INNERMOST budget of a five-link ladder; see Main.kt's STOP_DEADLINE_MS comment for the whole chain, and
// DaemonStopBudgetTest for the ordering that keeps each link below the next. The wait itself lives in
// TurnService.settle, which a gateway's provider runtime drains through too.

/** Serializes one head's admission drain, engine lifetime, and final store flush. */
internal class HeadLifecycle(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val engine: HeadEngine,
    private val turns: TurnService,
) {
    private val driver get() = turns.driver
    private val window get() = turns.window
    private val mutex = Mutex()
    private var drained = false

    // #523: set once this head has handed its books to the next daemon; from then on it writes none of them.
    private var retired = false

    suspend fun start(): Unit = mutex.withLock { startLocked() }
    suspend fun drain(): Unit = mutex.withLock { drainLocked() }
    suspend fun stop(): Unit = mutex.withLock { stopLocked() }
    suspend fun retire(): Int = mutex.withLock { retireLocked() }

    /** #523: waits for the answers a retired head kept, then stops it. The wait is outside the lock, so a stop that
     *  arrives meanwhile (the service being stopped) is not held behind it: that stop cuts what is still running with
     *  the restart line, as any stop does, and this then finds a head already closed. */
    suspend fun finish(withinMs: Long) {
        turns.awaitTurns(engine, withinMs)
        mutex.withLock { if (engine.isRunning) stopLocked() }
    }
    suspend fun restart(): Unit = mutex.withLock {
        stopLocked()
        // NF-01: restart clears whichever cooldown authority the turn path actually uses. Pooled
        // turns bypass the client-owned legacy cooldown, so reset every account instead.
        // Only a restart does: a head's first start keeps what its logins brought back from disk, a hold its provider
        // put on a login and the provider's word on its limits. On 0.4.1's promotion review (Nov 14, 2026) the reset
        // sat in every start, so the daemon's own boot undid the login holds #521 had just made survive a restart.
        deps.quotaBundle.activePool?.reset() ?: deps.traffic.upstream.clearRateLimitCooldown()
        startLocked()
    }

    private suspend fun startLocked() {
        if (engine.isRunning) return
        // A head that is up again has no spent drain behind it: the next stop drains afresh.
        drained = false
        // G20 contract: a control-plane restart promises a fresh diagnostic baseline; the counters
        // live on the long-lived TurnDriver, so reset them here (review 2026-07-19).
        // restart() is stop-then-start, so this reset alone suffices — a bare stop keeps counters intact.
        driver.resetHealth()
        driver.headStarted()
        engine.start()
        turns.open()
    }

    private suspend fun stopLocked() {
        drainLocked()
        closeLocked()
    }

    /** The half of a stop that keeps the port LISTENING while the running turns finish: a new turn meets a closed
     *  connection with no answer (AdmissionResponses.respondStopping), which Claude Code retries on a new connection
     *  without changing model. Idempotent — a stop after a drain closes without draining twice. */
    private suspend fun drainLocked() {
        if (drained) return
        // Refuse new turns FIRST so the drain can actually converge, then drain in-flight turns
        // so clients get honest terminals (driveSealingCancellation's cancellation seal) before
        // Netty tears the engine. Bounded wait — never block restart forever.
        engine.closeAdmission(window)
        turns.settle(engine)
        drained = true
    }

    /** #523: the cut of a restart that keeps the answers in progress. Admission closes first, as in a drain; then
     *  only the turns whose upstream has not begun answering are cut, and the turns whose client is gone with them. The
     *  books close HERE, before the daemon hands over: from [splice.head.perf.RetiredRows.retire] on, a row this
     *  process records is left for the next daemon to count, and the flushes after it are the last writes this
     *  process makes to the usage, hourly and session-total files. A provider that cannot finish a turn beside
     *  another daemon stops whole, here, so its own last flush lands before the hand-over too. */
    private suspend fun retireLocked(): Int {
        // A head already drained or already retired has no answer left to keep.
        val nothingToKeep = drained || retired
        if (nothingToKeep || !provider.finishesTurnsWhenRetired) {
            stopLocked()
            return 0
        }
        engine.closeAdmission(window)
        val kept = turns.retire()
        retired = true
        return kept
    }

    /** The half that closes the port and settles this head's books. Everything here is what a client
     *  can no longer be served through, so it runs as late as the daemon's stop can leave it. */
    private fun closeLocked() {
        engine.stop()
        turns.close(retired)
        // Spent: a head that starts again, or is stopped again, drains afresh.
        drained = false
    }
}
