package splice.app.observability

import splice.core.perf.observation.PerfSinkStatus
import splice.core.util.MonoClock

/** Direct writes have the same success boundary as queued file sinks, with at most one active writer. */
internal class PerfWriteStatus(private val name: String) {
    private var observed = 0L
    private var enqueued = 0L
    private var written = 0L
    private var failed = 0L
    private var dropped = 0L
    private var started: Long? = null
    private var successAt: Long? = null

    @Synchronized
    fun begin() { observed++; enqueued++; started = MonoClock.nowMs() }

    @Synchronized
    fun reject() { observed++; dropped++ }

    @Synchronized
    fun end(success: Boolean) {
        if (success) { written++; successAt = System.currentTimeMillis() } else failed++
        started = null
    }

    @Synchronized
    fun status(): PerfSinkStatus = PerfSinkStatus(
        name, observed, enqueued, written, failed, dropped, if (started == null) 0 else 1,
        started?.let { (MonoClock.nowMs() - it).coerceAtLeast(0) }, successAt,
    )
}
