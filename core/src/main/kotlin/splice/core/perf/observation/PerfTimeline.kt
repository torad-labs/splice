package splice.core.perf.observation

/** Epoch anchor plus monotonic deltas prevent wall-clock corrections from shifting an interval or making it negative. */
internal class PerfTimeline {
    private var origin: PerfStamp? = null
    var first = 0L
        private set
    var last = 0L
        private set
    var clockChanges = 0L
        private set
    val observed: Long get() = (last - first).coerceAtLeast(0)

    fun advance(stamp: PerfStamp): Long {
        val start = origin
        if (start == null) {
            origin = stamp
            first = stamp.wallMs.coerceAtLeast(0)
            last = first
        } else {
            val elapsed = (stamp.monotonicMs - start.monotonicMs).coerceAtLeast(0)
            val candidate = (first + elapsed).coerceAtLeast(first)
            if (candidate < last || kotlin.math.abs(candidate - stamp.wallMs) > CLOCK_TOLERANCE_MS) clockChanges++
            last = maxOf(last, candidate)
        }
        return last
    }
}

private const val CLOCK_TOLERANCE_MS = 1000L
