// NEW: V4-456 — atomic duration and epoch-start observations on one turn's monotonic origin.
package splice.core.perf

private val INTERVAL_START_KEYS = mapOf(
    PerfKeys.UP_GAP_MAX_MS to PerfKeys.UP_GAP_MAX_START_EPOCH_MS,
    PerfKeys.OUT_HOLD_MAX_MS to PerfKeys.OUT_HOLD_MAX_START_EPOCH_MS,
    PerfKeys.UP_WIRE_GAP_MAX_MS to PerfKeys.UP_WIRE_GAP_MAX_START_EPOCH_MS,
    PerfKeys.UP_READ_WAIT_MAX_MS to PerfKeys.UP_READ_WAIT_MAX_START_EPOCH_MS,
    PerfKeys.UP_READ_IDLE_MAX_MS to PerfKeys.UP_READ_IDLE_MAX_START_EPOCH_MS,
)

/** Rejects interval callbacks from retired upstream attempts. */
internal fun interface CurrentUpstreamAttempt {
    operator fun invoke(attempt: Long): Boolean
}

/** Publishes a duration and event kind through the turn's existing maximum recorder. */
internal fun interface IntervalMaximum {
    operator fun invoke(counter: String, value: Long, end: UpstreamGapEnd)
}

/** The monitor a turn's perf counters and paired intervals publish under, so one observation lands atomically. */
internal class TurnPerfLock

/** Paired interval observations share their owner's lock and snapshot; no second clock is sampled. */
public class PerfIntervals internal constructor(
    private val lock: TurnPerfLock,
    private val counters: MutableMap<String, Long>,
    private val epochOriginMs: Long,
    private val accepts: CurrentUpstreamAttempt,
    private val publish: IntervalMaximum,
) {
    /** Inputs use the owner's legacy elapsed origin. Ties keep the first interval and event kind. */
    public fun record(
        counter: String,
        fromMs: Long,
        toMs: Long,
        end: UpstreamGapEnd = UpstreamGapEnd.UNKNOWN,
        attempt: Long? = null,
    ) {
        synchronized(lock) {
            if (attempt != null && !accepts(attempt)) return
            val value = toMs - fromMs
            val previous = counters[counter]
            publish(counter, value, end)
            if (previous == null || value > previous) {
                INTERVAL_START_KEYS[counter]?.let { counters[it] = epochOriginMs + fromMs }
            }
        }
    }

    /** An untimed winning replacement has no start observation; never retain its predecessor's start. */
    internal fun unmeasured(counter: String) {
        INTERVAL_START_KEYS[counter]?.let(counters::remove)
    }
}
