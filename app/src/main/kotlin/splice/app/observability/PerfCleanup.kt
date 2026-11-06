package splice.app.observability

import splice.core.util.Cancellables
import splice.core.util.LogSink
import java.io.InterruptedIOException

/** An ordinary failure cannot skip another owner's close; cancellation, interruption and Errors still escape. */
internal class PerfCleanup(private val log: LogSink) {
    private val failures = LinkedHashSet<String>()

    fun run(owner: String, action: PerfCloseAction) {
        Cancellables.runCatchingBestEffort { action.close() }.onFailure { failure ->
            throwIfInterrupted(failure)
            failed(owner)
        }
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

private const val MAX_FAILURES = 32
