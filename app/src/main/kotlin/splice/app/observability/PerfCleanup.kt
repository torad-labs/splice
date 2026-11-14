package splice.app.observability

import splice.core.util.Cancellables
import splice.core.util.LogSink
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** An ordinary failure cannot skip another owner's close; cancellation, interruption and Errors still escape. */
internal class PerfCleanup(private val log: LogSink) {
    private val failures = LinkedHashSet<String>()

    /** How long each step took, by its owner. #525 (2026-11-13): a retiring daemon holds the daemon lock while the
     *  recorder closes, every command's requests wait for it, and its one figure (145 ms at one hand-over, 315 ms at
     *  the next) did not say which step it was. */
    private val took = LinkedHashMap<String, Long>()

    fun run(owner: String, action: PerfCloseAction) {
        val began = System.nanoTime()
        val outcome = Cancellables.runCatchingBestEffort { action.close() }
        synchronized(took) { took.merge(owner, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began), Long::plus) }
        outcome.onFailure { failure ->
            throwIfInterrupted(failure)
            failed(owner)
        }
    }

    /** Says once where a close that took [totalMs] spent it: its steps of [SAID_STEP_MS] or more, slowest first, and
     *  what no step accounts for (the producers' stop and the wait for queries in flight). */
    fun said(totalMs: Long) {
        val steps = synchronized(took) { took.entries.map { it.key to it.value } }
        val slow = steps.filter { it.second >= SAID_STEP_MS }.sortedByDescending { it.second }
        val rest = (totalMs - steps.sumOf { it.second }).coerceAtLeast(0L)
        val parts = slow.map { "${it.first} ${it.second}ms" } + "outside its steps ${rest}ms"
        Cancellables.runCatchingBestEffort { log("[perf] close took ${totalMs}ms: ${parts.joinToString()}\n") }
            .getOrElse { throwIfInterrupted(it) }
    }

    /** Teardown still releases later owners when a step must propagate cancellation or a fatal failure. */
    fun finish(vararg actions: PerfCloseAction) { finishAt(actions, 0) }

    private fun finishAt(actions: Array<out PerfCloseAction>, index: Int) {
        if (index == actions.size) return
        Cancellables.withCleanup({ finishAt(actions, index + 1) }) { actions[index].close() }
    }

    @Synchronized
    fun failed(owner: String) {
        if (failures.size < MAX_FAILURES) failures += "cleanup_$owner"
        Cancellables.runCatchingBestEffort { log("[perf] cleanup failed: $owner\n") }
            .getOrElse { throwIfInterrupted(it) }
    }

    @Synchronized
    fun issues(): List<String> = failures.toList()

    private fun throwIfInterrupted(failure: Throwable) {
        val interrupted = failure is InterruptedException || failure is InterruptedIOException ||
            Thread.currentThread().isInterrupted
        if (interrupted) {
            Thread.currentThread().interrupt()
            throw failure
        }
    }
}

internal fun interface PerfCloseAction {
    fun close()
}

// Keep thirty-two owner failure codes so repeated cleanup failures cannot grow the diagnostic list indefinitely.
private const val MAX_FAILURES = 32

// why: a step under ten milliseconds is not where a close of a tenth of a second or more went.
private const val SAID_STEP_MS = 10L
