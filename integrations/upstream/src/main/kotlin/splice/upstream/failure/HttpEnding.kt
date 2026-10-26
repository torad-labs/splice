// NEW: failure-handling slice 1 (2026-10-26). The retry loop's last HTTP failure, read once at its exit.
package splice.upstream.failure

import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Lift
import splice.core.failure.LimitWindow
import splice.core.usage.PlanLimit
import splice.upstream.transport.RetryOutcome
import splice.upstream.transport.VendorReading

/** What the loop's last HTTP failure [failed] reads as, decided once at its exit at [status]: the failure's own unless
 *  the exit names another (a credential refreshed and still refused reads 401). The classifier reads its body, and a
 *  401 naming a credential other than the one sent is the upstream's own failure ([ForeignCredential]). A payment the
 *  provider asks for is [FailureCause.Provider.NeedsPayment] though every retry layer saw the 429 V4-73 made it, and a
 *  limit carries the reset the answer stated and the plan window it named. Every cause quotes the provider only
 *  through what [VendorReading.said] kept of its body as it came, never through a sentence splice wrote over it. The
 *  provider's own words for the perf row (V4-444) are judged on the classifier's reading. No status means no answer
 *  came back, so no words. */
internal class HttpEnding(failed: RetryOutcome.Failed?, status: Int? = failed?.status) {
    private val body = failed?.text.orEmpty()
    private val reading = failed?.vendor ?: VendorReading()
    private val classified =
        UpstreamFailureClassifier.classify(FailureSource.HTTP, body, status, said = reading.said)
    private val owned = ForeignCredential.upstreamsOwn(classified, body, failed?.sent)
    val cause: FailureCause.Provider = when {
        reading.payment -> FailureCause.Provider.NeedsPayment(Lift.Unstated, subscription = false, reading.said)
        owned.cause is FailureCause.Provider.Limited ->
            FailureCause.Provider.Limited(reading.lift, windowOf(failed?.planLimit), reading.said)
        else -> owned.cause
    }
    val evidence: FailureEvidence =
        FailureEvidence(
            owned.message,
            status,
            status?.let { ForeignCredential.upstreamsWords(body, classified, failed?.sent) },
            body,
        )

    /** The window a plan limit's claim names, as a line says it: Anthropic's five_hour and seven_day, and a seven-day
     *  window of one model (seven_day_opus). Any other claim is a window the line does not name. */
    private fun windowOf(limit: PlanLimit?): LimitWindow? {
        val claim = limit?.claim ?: return null
        return when {
            claim == FIVE_HOUR_CLAIM -> LimitWindow.FIVE_HOUR
            claim == SEVEN_DAY_CLAIM -> LimitWindow.WEEKLY
            claim.startsWith("${SEVEN_DAY_CLAIM}_") -> LimitWindow.MODEL_WEEKLY
            else -> LimitWindow.OTHER
        }
    }
}

// Anthropic's unified claims, as PlanLimit's own reading of them spells them.
private const val FIVE_HOUR_CLAIM = "five_hour"
private const val SEVEN_DAY_CLAIM = "seven_day"
