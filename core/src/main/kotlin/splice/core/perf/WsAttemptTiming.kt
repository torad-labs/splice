// NEW: V4-456 — JDK send acceptance and decoded fragments, never unobserved socket/TLS writes.
package splice.core.perf

/** One WebSocket attempt on the turn clock. An early fragment cannot invent a post-accept wait. */
public class WsAttemptTiming(private val perf: TurnPerf) {
    private val lock = Any()
    private val attempt = perf.beginUpstreamAttempt()
    private val attemptStartedAt = perf.elapsedMs()
    private var acceptedAt: Long? = null
    private var fragmentAt: Long? = null
    private var earlyFragment = false
    private var requestedAt: Long? = null
    private var callbackAt: Long? = null
    private var previousFragmentAt: Long? = null

    /** Successful completion of the JDK send future, not completion of a socket write. */
    public fun sendAccepted() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            acceptedAt = at
            publish()
        }
    }

    /** The listener's demand, on the same monotonic clock. Rearming closes callback-side work. */
    public fun requested(atMs: Long) {
        val at = maxOf(attemptStartedAt, atMs - perf.clockOriginMs)
        synchronized(lock) {
            callbackAt?.let { perf.intervals.record(PerfKeys.UP_READ_IDLE_MAX_MS, it, at, attempt = attempt) }
            callbackAt = null
            requestedAt = at
        }
    }

    /** Every decoded text callback, even when the JSON event remains incomplete. */
    public fun firstFragment() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            val enteredAt = perf.elapsedMs()
            requestedAt?.let { perf.intervals.record(PerfKeys.UP_READ_WAIT_MAX_MS, it, enteredAt, attempt = attempt) }
            previousFragmentAt?.let {
                perf.intervals.record(PerfKeys.UP_WIRE_GAP_MAX_MS, it, enteredAt, attempt = attempt)
            }
            previousFragmentAt = enteredAt
            callbackAt = enteredAt
            requestedAt = null
            if (fragmentAt == null) {
                fragmentAt = at
                earlyFragment = acceptedAt == null
                publish()
            }
        }
    }

    private fun publish() {
        perf.recordUpstreamTiming(
            attempt,
            acceptedAt,
            fragmentAt.takeUnless { earlyFragment },
            UpstreamMilestones.WS_ACCEPTANCE,
        )
    }
}
