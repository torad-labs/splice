package splice.app.observability

import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.util.Cancellables
import java.util.concurrent.atomic.AtomicLong

/** Invoked under gate locks: sinks may only update bounded memory or offer to nonblocking bounded queues. */
internal class PerfFanout(private val observers: List<PerfObserver>) : PerfObserver {
    val failures = AtomicLong()
    val dropped = AtomicLong()
    var loss = PerfLoss { }

    override fun record(observation: PerfObservation): Boolean {
        var accepted = true
        observers.forEach { observer ->
            val result = Cancellables.runCatchingBestEffort { observer.record(observation) }
                .getOrElse { failures.incrementAndGet(); false }
            if (!result) {
                dropped.incrementAndGet()
                loss.mark(observation.scope.head)
            }
            accepted = result && accepted
        }
        return accepted
    }
}

internal fun interface PerfLoss {
    fun mark(head: String)
}
