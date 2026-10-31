// NEW: what one attempt meets on the wire, told to each party that follows it (Oct 26, 2026): the turn's perf timing
// when the turn is measured, and the vendor's route when it has one. UpstreamTimingBridge carries it to the OkHttp
// layer, the one place where the request's write and the response's headers are seen.
package splice.upstream.transport

import splice.core.perf.UpstreamAttemptTiming
import splice.upstream.VendorRoute

/** One attempt's milestones on the wire, told to [timing] and [route]. Bind one instance to one attempt. */
internal class AttemptMarks(private val timing: UpstreamAttemptTiming?, private val route: VendorRoute?) {
    /** The request's body was written whole and flushed to the socket. */
    fun written() {
        timing?.written()
        route?.written()
    }

    /** OkHttp's responseHeadersStart: the response's headers have been read. */
    fun headersStarted() {
        timing?.headersStarted()
        route?.headersArrived()
    }

    /** Entering a read of the response body. */
    fun readStarted() {
        timing?.readStarted()
    }

    /** A read of the response body returned bytes. */
    fun firstByte() {
        timing?.firstByte()
    }
}
