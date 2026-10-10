// PORT-OF: splice/app/Daemon.kt (HeadLifecycle.stopHeads, HEAD_STOP_BUDGET_MS) @ ed5c868 —
// invariants unchanged: the daemon shutdown's head-stop phase, kept separate so
// DaemonStopDeadlineTest can prove the two invariants a wedged head must not break. (1) PARALLELISM:
// the N blocking HeadServer.stop() engine stops run CONCURRENTLY on Dispatchers.IO instead of
// serializing on Main's single-thread runBlocking event loop. (2) DEADLINE: withTimeoutOrNull caps
// the whole phase at [budgetMs] so a head whose drain never converges cannot extend shutdown
// unboundedly, and [StopControl] still runs afterward even when the cap trips. A truly-
// uninterruptible thread is beyond this budget's reach — Main's halt watchdog is that guarantee.
package splice.app.head

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.DaemonBoundary
import splice.app.StopControl
import splice.core.head.Head
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.upstream.codemode.ProcessDispatchers

// The whole head-stop phase's deadline (see [HeadShutdown.stopHeads]). Kept below Main's
// STOP_DEADLINE_MS so the graceful stop + control shutdown finish before Main's hard halt
// watchdog would ever need to fire, and ABOVE HeadDeps' DEFAULT_STOP_DRAIN_MS so the drain owns the
// wait rather than being cancelled by this budget. V4-74 raised both together: the drain needs
// 45s to outlive a deepseek turn, so this sits at 50s. Change one, check the other.
internal const val HEAD_STOP_BUDGET_MS = 50_000L

// The close phase that follows the drain: engine stops and the stores' final flushes, no waiting on turns.
// Netty's own stop is bounded at 2.5s per head (HeadEngine) and the flushes are local writes, so 5s covers
// every head in parallel while keeping the whole phase inside Main's STOP_DEADLINE_MS (55s) above the drain's 50s.
internal const val CLOSE_BUDGET_MS = 5_000L

internal class HeadShutdown(
    // HD-19: where the N blocking HeadServer.stop() engine stops run. Was a hardcoded
    // Dispatchers.IO inside stopHeads; defaulted here to the same value, so shutdown is
    // unchanged and DaemonStopDeadlineTest can pin the phase to a dispatcher it controls.
    private val stopDispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
) {

    private val boundary = DaemonBoundary()

    internal suspend fun stopHeads(
        heads: Collection<Head>,
        budgetMs: Long,
        log: LogSink,
        stopControl: StopControl,
    ) {
        val stopFailureHandler = CoroutineExceptionHandler { _, e ->
            log("[daemon] head stop failed uncaught: ${e::class.simpleName}: ${e.message}\n")
        }
        withContext(stopDispatcher) {
            // PHASE 1, every head at once: refuse new turns and let the running ones finish, with every
            // port STILL LISTENING. Closing each head's listener the moment its own drain converged was
            // measured refusing connections on an idle head for the whole 45s its busy siblings drained
            // (2026-10-10: ports 3101/3102 refused from the stop's first second, while the control port
            // and the busy heads stayed bound to the end). A head that is up answers; it never refuses.
            withTimeoutOrNull(budgetMs) {
                eachHead(heads, stopFailureHandler, "drain", HeadStopStep { it.drain() })
            }
            // PHASE 2: now that no head can still be serving, close the ports and settle the books. The
            // drain above is already spent, so this converges without waiting out a second budget.
            withTimeoutOrNull(CLOSE_BUDGET_MS) {
                eachHead(heads, stopFailureHandler, "stop", HeadStopStep { it.stop() })
            }
        }
        stopControl()
    }

    private suspend fun eachHead(
        heads: Collection<Head>,
        onFailure: CoroutineExceptionHandler,
        phase: String,
        step: HeadStopStep,
    ) {
        supervisorScope {
            heads.forEach { head ->
                launch(onFailure) {
                    Cancellables.discard(
                        boundary.runCatchingDaemonBoundary { step(head) },
                        "shutdown: one head failing to $phase must not block the rest",
                    )
                }
            }
        }
    }
}

/** One half of a head's stop, as the shutdown phase drives it over every head at once. */
internal fun interface HeadStopStep {
    suspend operator fun invoke(head: Head)
}
