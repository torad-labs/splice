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
import splice.spi.ProcessDispatchers

// The whole head-stop phase's deadline (see [HeadShutdown.stopHeads]). Kept below Main's
// STOP_DEADLINE_MS so the graceful stop + control shutdown finish before Main's hard halt
// watchdog would ever need to fire.
internal const val HEAD_STOP_BUDGET_MS = 6_000L

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
            withTimeoutOrNull(budgetMs) {
                supervisorScope {
                    heads.forEach { head ->
                        launch(stopFailureHandler) {
                            Cancellables.discard(
                                boundary.runCatchingDaemonBoundary { head.stop() },
                                "shutdown: one head failing to stop must not block the rest",
                            )
                        }
                    }
                }
            }
        }
        stopControl()
    }
}
