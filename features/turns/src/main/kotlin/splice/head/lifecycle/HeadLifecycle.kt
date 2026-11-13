package splice.head.lifecycle

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.util.AsyncFileIo
import splice.head.HeadDeps
import splice.head.HeadEngine
import splice.head.StopSeal
import splice.head.admission.AdmissionWindow
import splice.head.turn.TurnDriver
import splice.upstream.Provider
import java.util.concurrent.TimeUnit

// End in-flight SSE turns with an honest terminal before tearing the engine.
//
// The drain's budget is HeadDeps' DEFAULT_STOP_DRAIN_MS, now 0: a restart cuts each running turn at once with the
// head's restart line rather than waiting for it, so the next daemon takes over in seconds (Marcos, Oct 29, 2026).
// The cut is sealed while the client socket is still open, so the client reads the line, never a torn stream. This
// is the INNERMOST budget of a five-link ladder; see Main.kt's STOP_DEADLINE_MS comment for the whole chain, and
// DaemonStopBudgetTest for the ordering that keeps each link below the next.
private const val STOP_DRAIN_POLL_MS = 50L

// The cut turn's seal AND its reply must land before Netty is torn down: a collect turn writes its 529 only after
// the seal (CollectTurn.respond), and the slot releases after that. 250ms came from a 34ms cut on an idle machine and
// lost the reply under a 21-minute parallel gate run (2026-10-09, HeadServerStopDrainTest [false]). 2s is sized for a
// loaded host and still fits the ladder: drain + 2s seal + 2.5s engine stop < HEAD_STOP_BUDGET_MS (50s).
// Empty heads skip this wait entirely, and a released slot ends it early.
private const val STOP_SEAL_NS = 2_000_000_000L

/** Serializes one head's admission drain, engine lifetime, and final store flush. */
internal class HeadLifecycle(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val engine: HeadEngine,
    private val driver: TurnDriver,
    private val window: AdmissionWindow,
) {
    private val mutex = Mutex()
    private var drained = false

    // #523: set once this head has handed its books to the next daemon; from then on it writes none of them.
    private var retired = false
    private val gate get() = deps.traffic.gate
    private val log get() = deps.log

    suspend fun start(): Unit = mutex.withLock { startLocked() }
    suspend fun drain(): Unit = mutex.withLock { drainLocked() }
    suspend fun stop(): Unit = mutex.withLock { stopLocked() }
    suspend fun retire(): Int = mutex.withLock { retireLocked() }

    /** #523: waits for the answers a retired head kept, then stops it. The wait is outside the lock, so a stop that
     *  arrives meanwhile (the service being stopped) is not held behind it: that stop cuts what is still running with
     *  the restart line, as any stop does, and this then finds a head already closed. */
    suspend fun finish(withinMs: Long) {
        StopSeal(gate, engine, deps.seams.waiter).settle(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(withinMs))
        mutex.withLock { if (engine.isRunning) stopLocked() }
    }
    suspend fun restart(): Unit = mutex.withLock {
        stopLocked()
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
        // NF-01: restart clears whichever cooldown authority the turn path actually uses. Pooled
        // turns bypass the client-owned legacy cooldown, so reset every account instead.
        deps.quotaBundle.activePool?.reset() ?: deps.traffic.upstream.clearRateLimitCooldown()
        engine.start()
        window.open()
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
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deps.policy.stopDrainMs)
        var inflight = gate.snapshot().inflight
        while (inflight > 0 && System.nanoTime() < deadlineNs) {
            deps.seams.waiter.wait(STOP_DRAIN_POLL_MS)
            inflight = gate.snapshot().inflight
        }
        if (inflight > 0) {
            log("[${provider.key}] stop: cutting inflight=$inflight with the restart line; forcing engine stop\n")
        }
        // A detached compaction OUTLIVES ITS CLIENT (TurnStreamer): its handed-off slot travels with the drive. One
        // that finished inside the budget released its slot and kept its recording for the retry. End only what is
        // STILL driving once the budget is spent.
        driver.stopActive()
        driver.stopDetached()
        val sealDeadlineNs = System.nanoTime() + STOP_SEAL_NS
        StopSeal(gate, engine, deps.seams.waiter).settle(sealDeadlineNs)
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
        provider.onHeadRetire()
        val kept = driver.retireActive()
        deps.stores.perfStats.retired.retire()
        // A row recorded before that moment counts its hour on the file lane, behind its own append: the lane runs
        // what it holds, and then the books are written once, whole.
        val _ = AsyncFileIo.drain()
        deps.stores.usageStore.freeze()
        deps.stores.economicsStore?.flushNow()
        deps.stores.perfStats.totals?.flushNow()
        retired = true
        log("[${provider.key}] retire: finishing $kept turn(s) already answering; the next daemon takes the rest\n")
        return kept
    }

    /** The half that closes the port and settles this head's books. Everything here is what a client
     *  can no longer be served through, so it runs as late as the daemon's stop can leave it. */
    private fun closeLocked() {
        engine.stop()
        provider.onHeadStop()
        driver.flushHeldRows()
        // #523: a retired head's books are the next daemon's since the hand-over; writing them here would put this
        // process's older copy over what the next daemon has counted since.
        if (!retired) {
            deps.stores.usageStore.flushNow()
            deps.stores.economicsStore?.flushNow()
            deps.stores.perfStats.totals?.flushNow()
        }
        // Spent: a head that starts again, or is stopped again, drains afresh.
        drained = false
    }
}
