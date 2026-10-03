// NEW: V4-456 — JDK send acceptance and decoded fragments, never unobserved socket/TLS writes.
package splice.core.perf

/** One WebSocket attempt on the turn clock. An early fragment cannot invent a post-accept wait. */
public class WsAttemptTiming(private val perf: TurnPerf) {
    private val lock = Any()
    private val attempt = perf.beginUpstreamAttempt()
    private var acceptedAt: Long? = null
    private var fragmentAt: Long? = null
    private var earlyFragment = false

    /** Successful completion of the JDK send future, not completion of a socket write. */
    public fun sendAccepted() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            acceptedAt = at
            publish()
        }
    }

    /** First decoded text fragment, even when the JSON event remains incomplete. */
    public fun firstFragment() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
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
