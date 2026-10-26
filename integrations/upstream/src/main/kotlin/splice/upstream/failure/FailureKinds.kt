// NEW: the classified-failure DTO and the transport it came from. Split from
// UpstreamFailureClassifier.kt so the regex object is not billed for the
// result types (concentration, 2026-08-19). Same-package FQCNs are unchanged.
package splice.upstream.failure

import splice.core.failure.ClientWire
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.turn.ErrorType

/**
 * [UpstreamFailureClassifier]'s verdict on one upstream failure: the capped vendor message and the sealed [cause],
 * which decides what the client is told (ClientWire) and whether the failure is transient. Slice 3 of the
 * failure-handling redesign replaces the classifier with each provider's reader of its own replies.
 */
public data class ClassifiedFailure(
    val message: String,
    /** WHICH failure this verdict is, authored HERE because this is where the evidence is. The classifier reads
     *  the status, the vendor's code and the body text, and every branch already knows which of those decided it.
     *  Required, with no default: a new branch that forgets it does not compile. */
    val cause: FailureCause.Provider,
    /** V4-117: the upstream status this verdict was read from, carried so a failure CAUSE can be
     *  derived from the truth instead of re-projected out of [type]. classify() has always RECEIVED
     *  the status and used it to decide; it simply never returned it, so a caller asking "was this a
     *  4xx or a 5xx" had to guess backwards through a lossy ErrorType. Null means statusless — an
     *  SSE-borne failure, or a caller that never had one — and that is a real answer, not a gap. */
    val status: Int? = null,
    /** A status-less failure whose vendor code is on neither the retryable list nor the deterministic one: an
     *  unknown, which splice re-sends once before the client is told, never [transient]'s full curve and never
     *  the client's own re-send loop. Retry default is total: an unknown is not written off, and not looped. */
    val retryOnce: Boolean = false,
) {
    /** The type the client reads for this failure once content reached it, as ClientWire decides it. */
    val type: ErrorType
        get() = ClientWire.ownType(cause)

    /** The cause is one a re-send can change. A fact about the cause, not a retry decision: the HTTP retry loop
     *  still owns status and attempt budgets, while a dialect may use the same fact to decide whether a failed
     *  streaming round can be re-POSTed. */
    val transient: Boolean
        get() = !cause.permanent

    /** Whether splice re-sends this failure itself: on [transient]'s curve, or once for an unknown code. */
    val resends: Boolean
        get() = transient || retryOnce

    /** The verdict as a stream's own error event carries it. A 5xx named in band is the overload the client
     *  re-sends, as the in-band table always read UPSTREAM_STATUS_5XX; over HTTP the same status is the api_error
     *  a stream=false request answers 502 with. The provider's words ride along either way. */
    val inBand: FailureCause.Provider
        get() = if (cause is FailureCause.Provider.ServerError) {
            FailureCause.Provider.Overloaded(Lift.Unstated, loading = false, cause.said)
        } else {
            cause
        }
}

/**
 * Which leg produced the failure, so a status-code rejection is tellable from one that arrived
 * mid-stream after the headers were already accepted.
 *
 * It gates the extraction step, not the verdict: [FailureSource.HTTP] parses the body as a vendor
 * error envelope (and can short-circuit on a gateway HTML page), while [FailureSource.SSE] hands the
 * raw text straight to the shared regexes — an SSE failure has no envelope to unwrap. Both legs then
 * run the SAME classifier, which is the invariant UpstreamFailureClassifier.kt's header exists for.
 */
public enum class FailureSource { HTTP, SSE }
