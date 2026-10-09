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
package splice.upstream.transport

import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.perf.PerfKeys
import splice.core.perf.TimedWork
import splice.core.perf.TurnPerf
import splice.core.perf.TurnPerfTiming
import splice.core.usage.PlanLimit
import splice.core.wire.HttpStatus
import splice.core.wire.RateLimitReply
import splice.upstream.BodyAmendment
import splice.upstream.ClientFrameEmitted
import splice.upstream.CredentialHeaders
import splice.upstream.RetryNotice
import splice.upstream.StreamRead
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.sse.WireObserver

/** Remaining whole-turn wait budget at the instant retry policy asks. */
public fun interface RemainingTurnWait {
    public operator fun invoke(): Long
}

/** Observes a credential refresh only after the provider persisted usable credentials. */
public fun interface AuthRefreshObserver {
    public operator fun invoke()
}

/** Observes the original HTTP status at header arrival, before normalization or body consumption. */
public fun interface ProviderAnswerObserver {
    public fun observed(status: Int, observedAtEpochMs: Long)

    /** Enriches that same answer only after a spent subscription window has been decoded. */
    public fun quotaRefused(status: Int, observedAtEpochMs: Long) {}
}

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
    /** Selected account's cooldown; null preserves the client's legacy single-account cooldown. */
    val rateLimitCooldown: RateLimitCooldown? = null,
    /** Null preserves the legacy per-post deadline for callers without an outer turn budget. */
    val remainingTurnWait: RemainingTurnWait? = null,
    val authRefreshObserver: AuthRefreshObserver = AuthRefreshObserver {},
    /** V4-174: hears every send of this post after it ends (headers redacted, body exact). Null —
     *  the default and every head that did not opt in — records nothing and allocates nothing. */
    val wire: WireObserver? = null,
) {
    /** True only for the passthrough dialect, whose client understands the provider's native 429. */
    public var relayRateLimitReplies: Boolean = false

    /** Another transport already refused this exact body as too large: the Responses WebSocket peer closed the
     *  round's socket with 1009 (RFC 6455: message too big) before its first event. A 4xx for the same body is
     *  then the second refusal of the same bytes, never retried, and read as an overflow (WsSizeRefusal). */
    public var bodyRefusedAsTooLarge: Boolean = false

    /** A native pooled refusal is recoverable only by choosing another login, never by retrying this one. */
    internal val nativePool: Boolean get() = relayRateLimitReplies && rateLimitCooldown != null

    /** Delivered only after the provider accepts the HTTP request, before consuming its stream. */
    public var upstreamAccepted: splice.upstream.StreamStart = splice.upstream.StreamStart {}

    /** Passive metadata only; a caller without an observer retains the existing transport behavior. */
    public var providerAnswerObserver: ProviderAnswerObserver = ProviderAnswerObserver { _, _ -> }

    internal fun observeQuotaRefusal(status: Int, at: Long, plan: PlanLimit?) {
        if (status == HttpStatus.TOO_MANY_REQUESTS && plan != null) providerAnswerObserver.quotaRefused(status, at)
    }

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

    /** The credentials to send, or null when there are none locally: the call ends with [UpstreamAuthMissing]. */
    internal suspend fun requireAuth(): Credentials? = timedAuth { auth.credentials() }
}

/** Credentials and their resolved headers are captured together once, before selecting their hold. */
internal data class AttemptCredentials(
    val credentials: Credentials,
    val headers: Map<String, String>,
    val postedAtMs: Long?,
    val cooldown: RateLimitCooldown,
)

/** One attempt's result. [Failed] is produced INSIDE `attemptRequest`'s execute block — the
 *  response body channel dies at that block's close, so status, body text and Retry-After are all
 *  read there — and then lives on in the loop's `lastErr` as the failure the turn gives up with. */
internal sealed class RetryOutcome<out T> {
    data class Done<T>(val read: StreamRead<T>) : RetryOutcome<T>()
    data class Failed(
        val status: Int,
        val text: String,
        val retryAfterMs: Long? = null,
        /** V4-233: a 429 whose unified headers name a spent plan window, read here because the
         *  headers die with the response like the body does. Null for every other failure. */
        val planLimit: PlanLimit? = null,
        val rateLimitReply: RateLimitReply? = null,
    ) : RetryOutcome<Nothing>()
}
