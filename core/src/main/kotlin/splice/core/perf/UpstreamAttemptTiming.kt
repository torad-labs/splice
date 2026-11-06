// NEW: V4-456 — transport milestones for one attempt, measured on the turn's monotonic clock.
package splice.core.perf

import splice.core.perf.observation.PerfEventKind

/** A successful SSE request flush and first positive body read, never producer or event-queue time.
 *  Bind one instance to one attempt. Missing milestones remain absent in the snapshot. */
public class UpstreamAttemptTiming(private val perf: TurnPerf) {
    private val observations = perf.observations?.let {
        it.update(it.scope.copy(transport = "http"))
        it.attempt("http")
    }
    private val lock = Any()
    private var writtenAt: Long? = null
    private var firstByteAt: Long? = null
    private var headersStartedAt: Long? = null
    private var headersDeliveredAt: Long? = null
    private var readCallAt: Long? = null
    private var readReturnedAt: Long? = null
    private val attempt = perf.upstream.begin(observations)

    public fun finish(outcome: String) {
        observations?.finish(outcome)
    }

    /** Called only after the SSE request's final body bytes have flushed successfully. */
    public fun written() {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            if (writtenAt == null) {
                observations?.observe(PerfEventKind.PROTOCOL_PROGRESS, mapOf("http_request_write_ms" to at.toDouble()))
            }
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
            if (headersDeliveredAt == null) {
                observations?.observe(
                    PerfEventKind.PROTOCOL_PROGRESS,
                    mapOf("http_response_headers_ms" to at.toDouble()),
                )
            }
            headersDeliveredAt = at
            publish()
        }
    }

    private fun publish() {
        perf.upstream.record(attempt, writtenAt, firstByteAt)
        perf.upstream.record(
            attempt,
            headersStartedAt,
            headersDeliveredAt,
            UpstreamMilestones.SSE_HEADERS,
        )
    }

    /** Entering Source.read, before any blocking read work. A previous positive return bounds local idle. */
    public fun readStarted() {
        val at = perf.elapsedMs()
        synchronized(lock) {
            readReturnedAt?.let { perf.intervals.record(PerfKeys.UP_READ_IDLE_MAX_MS, it, at, attempt = attempt) }
            readCallAt = at
        }
    }

    /** Every positive read completion, before event decoding. The first also closes the write/read pair. */
    public fun firstByte(bytes: Long = 0L) {
        val at = perf.arrivalElapsedMs()
        synchronized(lock) {
            if (bytes > 0L) {
                observations?.observe(
                    PerfEventKind.PROTOCOL_PROGRESS,
                    mapOf("upstream_bytes" to bytes.toDouble()),
                )
            }
            val returnedAt = perf.elapsedMs()
            readCallAt?.let { perf.intervals.record(PerfKeys.UP_READ_WAIT_MAX_MS, it, returnedAt, attempt = attempt) }
            readReturnedAt?.let {
                perf.intervals.record(PerfKeys.UP_WIRE_GAP_MAX_MS, it, returnedAt, attempt = attempt)
            }
            readReturnedAt = returnedAt
            readCallAt = null
            if (firstByteAt == null) {
                observations?.observe(
                    PerfEventKind.PROTOCOL_PROGRESS,
                    mapOf("http_first_body_byte_ms" to at.toDouble()),
                )
                firstByteAt = at
                publish()
            }
        }
    }
}
