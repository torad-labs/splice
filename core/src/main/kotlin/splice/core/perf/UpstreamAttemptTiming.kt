// NEW: V4-456 — transport milestones for one attempt, measured on the turn's monotonic clock.
package splice.core.perf

/** A successful SSE request flush and first positive body read, never producer or event-queue time.
 *  Bind one instance to one attempt. Missing milestones remain absent in the snapshot. */
public class UpstreamAttemptTiming(private val perf: TurnPerf) {
    private val lock = Any()
    private var writtenAt: Long? = null
    private var firstByteAt: Long? = null
    private val attempt = perf.beginUpstreamAttempt()

    /** Called only after the SSE request's final body bytes have flushed successfully. */
    public fun written() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            writtenAt = at
            perf.recordUpstreamTiming(attempt, writtenAt, firstByteAt)
        }
    }

    /** Called at the first positive upstream read, before decoding or downstream delivery. */
    public fun firstByte() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            if (firstByteAt == null) {
                firstByteAt = at
                perf.recordUpstreamTiming(attempt, writtenAt, firstByteAt)
            }
        }
    }
}
