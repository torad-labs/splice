// NEW: V4-456 — transport milestones for one attempt, measured on the turn's monotonic clock.
package splice.core.perf

/** A successful SSE request flush and first positive body read, never producer or event-queue time.
 *  Bind one instance to one attempt. Missing milestones remain absent in the snapshot. */
public class UpstreamAttemptTiming(private val perf: TurnPerf) {
    private val lock = Any()
    private var writtenAt: Long? = null
    private var firstByteAt: Long? = null
    private var headersStartedAt: Long? = null
    private var headersDeliveredAt: Long? = null
    private val attempt = perf.beginUpstreamAttempt()

    /** Called only after the SSE request's final body bytes have flushed successfully. */
    public fun written() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            writtenAt = at
            publish()
        }
    }

    /** OkHttp's responseHeadersStart runs after headers return, before the Ktor response callback.
     *  Repeated header events overwrite this value so redirects and informational responses do not
     *  leave the final handoff measured from an earlier response. */
    public fun headersStarted() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            headersStartedAt = at
            publish()
        }
    }

    /** Called at Ktor's response receipt, independently of its response-body reader. */
    public fun headersDelivered() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            headersDeliveredAt = at
            publish()
        }
    }

    private fun publish() {
        perf.recordUpstreamTiming(attempt, writtenAt, firstByteAt)
        perf.recordUpstreamTiming(
            attempt,
            headersStartedAt,
            headersDeliveredAt,
            UpstreamMilestones.SSE_HEADERS,
        )
    }

    /** Called at the first positive upstream read, before decoding or downstream delivery. */
    public fun firstByte() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            if (firstByteAt == null) {
                firstByteAt = at
                publish()
            }
        }
    }
}
