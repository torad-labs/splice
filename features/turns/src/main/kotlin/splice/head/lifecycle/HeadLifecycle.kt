package splice.head.lifecycle

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.head.HeadDeps
import splice.head.HeadEngine
import splice.head.StopSeal
import splice.head.admission.AdmissionWindow
import splice.head.turn.TurnDriver
import splice.upstream.Provider
import java.util.concurrent.TimeUnit

// Wait for in-flight SSE turns to finish (or cancel cleanly) before tearing the engine.
//
// V4-74: 45s, and the reason is a MEASURED turn length rather than a round number — the operator's
// deepseek turns run 7 to 16s, so the old 5s drain could not outlive even a short one and every
// restart cut the turn mid-stream, which Claude Code does NOT retry after content (it prints
// "API Error: Connection lost mid-response"). This is the INNERMOST budget of a five-link ladder;
// see Main.kt's STOP_DEADLINE_MS comment for the whole chain, and DaemonStopBudgetTest for the
// ordering that keeps each link below the next.
private const val STOP_DRAIN_POLL_MS = 50L

// The cut turn's seal AND its reply must land before Netty is torn down: a collect turn writes its 529 only after
// the seal (CollectTurn.respond), and the slot releases after that. 250ms came from a 34ms cut on an idle machine and
// lost the reply under a 21-minute parallel gate run (2026-10-09, HeadServerStopDrainTest [false]). 2s is sized for a
// loaded host and still fits the ladder: 45s drain + 2s seal + 2.5s engine stop < HEAD_STOP_BUDGET_MS (50s).
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
    private val gate get() = deps.traffic.gate
    private val log get() = deps.log

    suspend fun start(): Unit = mutex.withLock { startLocked() }
    suspend fun drain(): Unit = mutex.withLock { drainLocked() }
    suspend fun stop(): Unit = mutex.withLock { stopLocked() }
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
            log("[${provider.key}] stop: draining timed out with inflight=$inflight; forcing engine stop\n")
        }
        // A detached compaction OUTLIVES ITS CLIENT (TurnStreamer): its handed-off slot travels with
        // the drive, and the drain budget above belongs to that feature — a detached compaction that
        // finishes inside the budget releases its slot and keeps its recording for the retry. End
        // only what is STILL driving once the budget is spent.
        driver.stopActive()
        driver.stopDetached()
        val sealDeadlineNs = System.nanoTime() + STOP_SEAL_NS
        StopSeal(gate, engine, deps.seams.waiter).settle(sealDeadlineNs)
        drained = true
    }

    /** The half that closes the port and settles this head's books. Everything here is what a client
     *  can no longer be served through, so it runs as late as the daemon's stop can leave it. */
    private fun closeLocked() {
        engine.stop()
        provider.onHeadStop()
        driver.flushHeldRows()
        deps.stores.usageStore.flushNow()
        deps.stores.economicsStore?.flushNow()
        deps.stores.perfStats.totals?.flushNow()
        // Spent: a head that starts again, or is stopped again, drains afresh.
        drained = false
    }
}
