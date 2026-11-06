package splice.head.transport

import splice.core.perf.TurnPerf
import splice.core.perf.UpstreamAttempts
import splice.core.perf.observation.PerfAttemptRecorder
import splice.core.perf.observation.PerfEventKind
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One logical-reader tail, closed by the transport before its handle or by the reader's finally. */
internal class UpstreamEventTail(
    private val perf: TurnPerf,
    private val observations: PerfAttemptRecorder?,
    postedAtMs: Long?,
) : UpstreamAttempts.TailObservation {
    private val previous = AtomicReference(postedAtMs)
    private val recorded = AtomicBoolean(false)

    /** Synchronous event delivery has finished; that blocked time is not upstream silence. */
    fun delivered(atMs: Long) {
        previous.set(atMs)
    }

    override fun record() {
        if (!recorded.compareAndSet(false, true)) return
        val from = previous.get() ?: return
        val gap = (perf.elapsedMs() - from).coerceAtLeast(0)
        observations?.observe(PerfEventKind.GAP, mapOf("upstream_gap_ms" to gap.toDouble()))
    }
}
