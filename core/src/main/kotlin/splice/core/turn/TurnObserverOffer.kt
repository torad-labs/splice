package splice.core.turn

import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

/** Run a trusted, synchronous queue offer as its own task, so the offer's failure is not the turn's
 * coroutine failure. The result read is non-waiting; only successful offers have a result. */
public object TurnObserverOffer {
    /** Delivers an offer with no result to read. */
    public fun deliver(offer: Runnable) {
        FutureTask(offer, Unit).run()
    }

    public fun <T> isolated(offer: Callable<T>): T? {
        val task = FutureTask(offer)
        task.run()
        return if (task.state() == Future.State.SUCCESS) task.resultNow() else null
    }
}
