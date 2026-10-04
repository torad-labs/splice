// NEW: the classified-failure DTO and the transport it came from. Split from
// UpstreamFailureClassifier.kt so the regex object is not billed for the
// result types (concentration, 2026-08-19). Same-package FQCNs are unchanged.
package splice.upstream.failure

import splice.core.turn.ErrorType
import splice.core.turn.FailureCause

/**
 * [UpstreamFailureClassifier]'s verdict on one upstream failure: the [ErrorType] the client is told,
 * the capped vendor message, and whether the cause is explicitly transient. [transient] is a fact
 * about the cause, not a retry decision: the HTTP retry loop still owns status/attempt budgets, while
 * a dialect may use the same fact to decide whether a failed streaming round can be re-POSTed.
 */
public data class ClassifiedFailure(
    val type: ErrorType,
    val message: String,
    val transient: Boolean = false,
    /** V4-117: the upstream status this verdict was read from, carried so a failure CAUSE can be
     *  derived from the truth instead of re-projected out of [type]. classify() has always RECEIVED
     *  the status and used it to decide; it simply never returned it, so a caller asking "was this a
     *  4xx or a 5xx" had to guess backwards through a lossy ErrorType. Null means statusless — an
     *  SSE-borne failure, or a caller that never had one — and that is a real answer, not a gap. */
    val status: Int? = null,
    /** V4-117: WHICH failure this verdict is, authored HERE because this is where the evidence is.
     *  The classifier reads the status, the vendor's code and the body text, and every branch below
     *  already knows which of those decided it — so it can name the cause directly instead of a
     *  boundary consumer re-deriving one backwards out of [type], which is lossy in exactly the
     *  direction that matters (a policy refusal and a malformed request are both INVALID_REQUEST).
     *  Required, with no default: a new branch that forgets it does not compile. */
    val cause: FailureCause,
)

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
