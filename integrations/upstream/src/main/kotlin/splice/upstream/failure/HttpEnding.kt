// NEW: failure-handling slice 1 (2026-10-26). The retry loop's last HTTP failure, read once at its exit.
package splice.upstream.failure

import splice.core.auth.Credentials
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence

/** What the loop's last HTTP failure reads as, decided once at its exit: the classifier's reading of [body] at
 *  [status], where a 401 naming a credential other than the one [sent] is the upstream's own failure
 *  ([ForeignCredential]), and the provider's own words for the perf row (V4-444), judged on the reading before that
 *  rewrite. No status means no answer came back, so no words. */
internal class HttpEnding(body: String, status: Int?, sent: Credentials?) {
    private val classified = UpstreamFailureClassifier.classify(FailureSource.HTTP, body, status)
    private val reading = ForeignCredential.upstreamsOwn(classified, body, sent)
    val cause: FailureCause.Provider = reading.cause
    val evidence: FailureEvidence =
        FailureEvidence(
            reading.message,
            status,
            status?.let { ForeignCredential.upstreamsWords(body, classified, sent) },
            body,
        )
}
