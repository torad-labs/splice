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
import kotlinx.coroutines.async
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// The whole head-stop phase's deadline (see [HeadShutdown.stopHeads]). Kept below Main's
// STOP_DEADLINE_MS so the graceful stop + control shutdown finish before Main's hard halt
// watchdog would ever need to fire, and ABOVE HeadDeps' DEFAULT_STOP_DRAIN_MS so the drain owns the
// wait rather than being cancelled by this budget. V4-74 raised both together: the drain needs
// 45s to outlive a deepseek turn, so this sits at 50s. Change one, check the other.
internal const val HEAD_STOP_BUDGET_MS = 50_000L

// The close phase that follows the drain: engine stops and the stores' final flushes, no waiting on turns.
// Each engine stop is bounded by HeadEngine's 0.5s grace and 2s timeout, its application's disposal included (Ktor
// waited its default 5s there until Oct 19), and the flushes are local writes, so 5s covers every head in parallel
// while keeping the whole phase inside Main's STOP_DEADLINE_MS (55s) above the drain's 50s. This budget cannot cut a
// blocking engine stop short: the coroutines it cancels are inside one, so the engines' own bounds are what hold.
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
        val stopFailureHandler = failureHandler(log)
        val began = System.nanoTime()
        val elapsedMs = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) }
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
            val drainedMs = elapsedMs()
            withTimeoutOrNull(CLOSE_BUDGET_MS) {
                eachHead(heads, stopFailureHandler, "stop", HeadStopStep { it.stop() })
            }
            // Where a stop spent its time, so an overrun names its phase in the journal (Oct 19: the halt cut every
            // planned restart at 57s, and no line said which phase held it).
            log("[daemon] stop: heads drained at ${drainedMs}ms, closed at ${elapsedMs()}ms\n")
        }
        stopControl()
        log("[daemon] stop: control closed at ${elapsedMs()}ms\n")
    }

    /** #523: the hand-over's head phase. Every head at once stops taking turns, cuts the ones its upstream has not
     *  begun answering and closes its books; a head that can finish its answers beside the next daemon keeps them
     *  running. Control leaves beside them ([leaveControl]). Returns how many turns are still being answered. */
    internal suspend fun retireHeads(
        heads: Collection<Head>,
        budgetMs: Long,
        log: LogSink,
        leaveControl: StopControl,
    ): Int {
        val kept = AtomicInteger(0)
        val began = System.nanoTime()
        withContext(stopDispatcher) {
            supervisorScope {
                // #525 (2026-11-13): the daemon lock is held until this returns, and every command's requests wait for
                // it. The control server's whole stop was part of it: after the heads at first, then beside them. That
                // stop waits out its grace, 100 ms, and up to its timeout, 500 ms, for a call still answering, and the
                // heads retire faster than either: at three restarts that day the last head had said it retired 16 to
                // 27 ms in, and the hand-over ended at 107, 160 and 522 ms, when control had closed. Control now only
                // leaves here: it stops taking connections, so a request sent from here on waits for the next daemon,
                // and its engine stops in finishHeads, once the lock is freed. The next daemon's ask was answered, and
                // what rides on the control listener was stopped, before the heads were asked to retire
                // (Daemon.retire).
                val control = async { leaveControl() }
                withTimeoutOrNull(budgetMs) {
                    eachHead(heads, failureHandler(log), "retire", HeadStopStep { kept.addAndGet(it.retire()) })
                }
                // A control server that failed to leave is thrown here, once the heads have retired.
                control.await()
            }
        }
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        log("[daemon] retire: heads handed over and control left at ${tookMs}ms, ${kept.get()} turn(s) finishing\n")
        return kept.get()
    }

    /** #523: waits up to [withinMs] for the answers [retireHeads] kept, every head at once, and stops each head as
     *  its last one ends. The control server's engine stops beside them (#525): the hand-over only had it stop taking
     *  connections, so the lock was not held for that stop. Cancelling it leaves the heads to the stop that follows. */
    internal suspend fun finishHeads(heads: Collection<Head>, withinMs: Long, log: LogSink, stopControl: StopControl) {
        val began = System.nanoTime()
        val elapsedMs = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) }
        withContext(stopDispatcher) {
            supervisorScope {
                val control = async {
                    stopControl()
                    log("[daemon] retire: control closed ${elapsedMs()}ms after the lock was freed\n")
                }
                eachHead(heads, failureHandler(log), "finish", HeadStopStep { it.finish(withinMs) })
                control.await()
            }
        }
        log("[daemon] retire: every kept turn ended at ${elapsedMs()}ms\n")
    }

    private fun failureHandler(log: LogSink) = CoroutineExceptionHandler { _, e ->
        log("[daemon] head stop failed uncaught: ${e::class.simpleName}: ${e.message}\n")
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
