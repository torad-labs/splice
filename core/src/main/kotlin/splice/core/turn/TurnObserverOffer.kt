package splice.core.turn

import splice.core.util.LogSink
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/** Run a trusted, synchronous queue offer as its own task, so the offer's failure is not the turn's
 * coroutine failure. The result read is non-waiting; only successful offers have a result. */
public class TurnObserverOffer(private val log: LogSink) {
    private val reported = AtomicBoolean()

    /** Delivers an offer with no result to read. */
    public fun deliver(offer: Runnable) {
        val task = FutureTask(offer, Unit)
        task.run()
        reportFailure(task)
    }

    public fun <T> isolated(offer: Callable<T>): T? {
        val task = FutureTask(offer)
        task.run()
        reportFailure(task)
        return if (task.state() == Future.State.SUCCESS) task.resultNow() else null
    }

    private fun reportFailure(task: Future<*>) {
        if (task.state() == Future.State.FAILED && reported.compareAndSet(false, true)) {
            // Exception messages can quote input or credentials.
            log("[mods] turn observation failed; further failures omitted\n")
        }
    }
}
