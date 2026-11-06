// NEW: V4-456 — JDK send acceptance and decoded fragments, never unobserved socket/TLS writes.
package splice.core.perf

import splice.core.perf.observation.PerfEventKind

/** One WebSocket attempt on the turn clock. An early fragment cannot invent a post-accept wait. */
public class WsAttemptTiming(private val perf: TurnPerf, private val recordLegacy: Boolean = true) {
    private val observations = perf.observations?.let {
        if (recordLegacy) it.update(it.scope.copy(transport = "ws"))
        it.attempt("ws")
    }
    private val lock = Any()
    private val attempt = if (recordLegacy) perf.upstream.begin(observations) else 0L
    private val attemptStartedAt = perf.elapsedMs()
    private var acceptedAt: Long? = null
    private var fragmentAt: Long? = null
    private var earlyFragment = false
    private var requestedAt: Long? = null
    private var latestDemandAt: Long? = null
    private var callbackAt: Long? = null
    private var previousFragmentAt: Long? = null

    public fun finish(outcome: String) {
        observations?.finish(outcome)
    }

    /** UTF-8 text payload bytes, excluding unobserved websocket framing and TLS overhead. */
    public fun bytes(count: Long) {
        observations?.observe(PerfEventKind.PROTOCOL_PROGRESS, mapOf("upstream_bytes" to count.toDouble()))
    }

    /** A decoded JSON protocol event, including prefills that are consumed outside the client turn flow. */
    public fun event() {
        observations?.observe(PerfEventKind.PROTOCOL_PROGRESS, mapOf("upstream_events" to 1.0))
    }

    /** Successful completion of the JDK send future, not completion of a socket write. */
    public fun sendAccepted() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            if (acceptedAt == null) {
                observations?.observe(PerfEventKind.PROTOCOL_PROGRESS, mapOf("ws_send_accepted_ms" to at.toDouble()))
            }
            acceptedAt = at
            publish()
        }
    }

    /** The listener's demand, on the same monotonic clock. Rearming closes callback-side work. */
    public fun requested(atMs: Long) {
        val at = maxOf(attemptStartedAt, atMs - perf.clockOriginMs)
        synchronized(lock) {
            // Binding can replay an idle demand after a newer callback has rearmed the listener.
            if (latestDemandAt?.let { at < it } == true) return
            latestDemandAt = at
            if (recordLegacy) {
                callbackAt?.let { perf.intervals.record(PerfKeys.UP_READ_IDLE_MAX_MS, it, at, attempt = attempt) }
            }
            callbackAt = null
            requestedAt = at
        }
    }

    /** Every decoded text callback, even when the JSON event remains incomplete. */
    public fun firstFragment() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            val enteredAt = perf.elapsedMs()
            if (recordLegacy) {
                requestedAt?.let {
                    perf.intervals.record(
                        PerfKeys.UP_READ_WAIT_MAX_MS,
                        it,
                        enteredAt,
                        attempt = attempt,
                    )
                }
                previousFragmentAt?.let {
                    perf.intervals.record(PerfKeys.UP_WIRE_GAP_MAX_MS, it, enteredAt, attempt = attempt)
                }
            }
            previousFragmentAt = enteredAt
            callbackAt = enteredAt
            requestedAt = null
            if (fragmentAt == null) {
                observations?.observe(PerfEventKind.PROTOCOL_PROGRESS, mapOf("ws_first_fragment_ms" to at.toDouble()))
                fragmentAt = at
                earlyFragment = acceptedAt == null
                publish()
            }
        }
    }

    /** The peer refused this attempt's request frame as too large: a WebSocket close 1009 before any event. */
    public fun refusedAsTooLarge() {
        if (recordLegacy) perf.add(PerfKeys.WS_REFUSED_TOO_LARGE, 1)
    }

    private fun publish() {
        if (!recordLegacy) return
        perf.upstream.record(
            attempt,
            acceptedAt,
            fragmentAt.takeUnless { earlyFragment },
            UpstreamMilestones.WS_ACCEPTANCE,
        )
    }
}
