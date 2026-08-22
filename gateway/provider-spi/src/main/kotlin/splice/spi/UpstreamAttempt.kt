// PORT-OF: splice/spi/UpstreamClient.kt (PostContext, RetryOutcome) @ 3879c4c — invariants unchanged: de-nested, not re-derived; RetryOutcome.Failed is still built INSIDE the execute block, and the four budgets did not come with them.
//
// ONE ATTEMPT, as data (HD-25): what every attempt is handed, and what it hands back.
//
// Both were nested inside UpstreamClient. They are top-level here because THREE files now read
// them — the loop (UpstreamClient.kt), the decision (RetryPolicy.kt) and, through [RetryOutcome],
// the give-up exit — and a nested type read from outside its owner is the published-nested-type
// coupling this campaign exists to remove. Nothing about their content changed.
//
// What did NOT come with them: [UpstreamClient.RetryState], which holds the loop's four mutually
// independent budgets, and the LoopStep control flow. Those are mutated, and mutation stays in one
// file.
package splice.spi

import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.perf.PerfKeys
import splice.core.perf.TimedWork
import splice.core.perf.TurnPerf
import splice.core.perf.TurnPerfTiming

/** The per-post collaborators threaded through every attempt (grouped: one cohesive argument).
 *  Callers construct this and pass it to [UpstreamClient.post]. */
public data class PostContext(
    val url: String,
    val auth: RefreshableAuthProvider,
    val extraHeaders: CredentialHeaders,
    val onRetry: RetryNotice = RetryNotice {},
    val perf: TurnPerf? = null,
    val clientFrameEmitted: ClientFrameEmitted = ClientFrameEmitted { true },
    val amendBodyOnFailure: BodyAmendment = BodyAmendment { _, _, _ -> null },
) {
    internal fun markRetry() {
        perf?.add(PerfKeys.RETRIES, 1)
    }

    internal fun markAttempt() {
        perf?.add(PerfKeys.ATTEMPTS, 1)
    }

    internal fun markPostSendRetry() {
        perf?.add(PerfKeys.POST_SEND_RETRIES, 1)
    }

    internal fun markHeaders() {
        perf?.mark(PerfKeys.HEADERS)
    }

    internal suspend fun <T> timedAuth(block: TimedWork<T>): T =
        TurnPerfTiming.timedOr(perf, PerfKeys.AUTH_MS, block)

    internal suspend fun <T> timedBackoff(block: TimedWork<T>): T =
        TurnPerfTiming.timedOr(perf, PerfKeys.BACKOFF_MS, block)

    internal suspend fun requireAuth(): Credentials =
        timedAuth { auth.credentials() } ?: throw UpstreamAuthMissing()
}

/** One attempt's result. [Failed] is produced INSIDE `attemptRequest`'s execute block — the
 *  response body channel dies at that block's close, so status, body text and Retry-After are all
 *  read there — and then lives on in the loop's `lastErr` as the failure the turn gives up with. */
internal sealed class RetryOutcome<out T> {
    data class Done<T>(val value: T) : RetryOutcome<T>()
    data class Failed(
        val status: Int,
        val text: String,
        val retryAfterMs: Long? = null,
    ) : RetryOutcome<Nothing>()
}
