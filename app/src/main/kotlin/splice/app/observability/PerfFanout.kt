package splice.app.observability

import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.util.Cancellables
import java.util.concurrent.atomic.AtomicLong

/** Invoked under gate locks: sinks may only update bounded memory or offer to nonblocking bounded queues. */
internal class PerfFanout(private val targets: List<PerfFanoutTarget>) : PerfObserver {
    val failures = AtomicLong()
    val dropped = AtomicLong()

    override fun record(observation: PerfObservation): Boolean {
        var accepted = true
        targets.forEach { target ->
            val result = Cancellables.runCatchingBestEffort { target.observer.record(observation) }
                .getOrElse { failures.incrementAndGet(); false }
            if (!result) {
                dropped.incrementAndGet()
                target.loss?.mark(observation.scope.head)
            }
            accepted = result && accepted
        }
        return accepted
    }
}

/** A rejected observation invalidates only the coverage owned by its destination. */
internal data class PerfFanoutTarget(val observer: PerfObserver, val loss: PerfLoss? = null)

internal fun interface PerfLoss {
    fun mark(head: String)
}
