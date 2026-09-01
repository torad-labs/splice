// NEW: the classified-failure DTO and the transport it came from. Split from
// UpstreamFailureClassifier.kt so the regex object is not billed for the
// result types (concentration, 2026-08-19). Same-package FQCNs are unchanged.
package splice.spi

import splice.core.turn.ErrorType

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
