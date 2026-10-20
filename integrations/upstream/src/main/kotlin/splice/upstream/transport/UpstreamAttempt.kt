// PORT-OF: splice/spi/UpstreamClient.kt (PostContext, RetryOutcome) @ 3879c4c — invariants unchanged: de-nested, not re-derived; RetryOutcome.Failed is still built INSIDE the execute block, and the four budgets did not come with them.
//
// ONE ATTEMPT, as data (HD-25): what every attempt is handed, and what it hands back.
//
// Both were nested inside UpstreamClient. They are top-level here because THREE files now read
// them — the loop (UpstreamClient.kt), the decision (RetryPolicy.kt) and, through [RetryOutcome],
// the give-up exit — and a nested type read from outside its owner is the published-nested-type
// coupling this campaign exists to remove. Nothing about their content changed. The groups the context carries
// (PostRecovery, PostLimits, PostObservers) and the three seams they hold live in PostGroups.kt.
//
// What did NOT come with them: [UpstreamClient.RetryState], which holds the loop's four mutually
// independent budgets, and the LoopStep control flow. Those are mutated, and mutation stays in one
// file.
package splice.upstream.transport

import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.perf.PerfKeys
import splice.core.perf.TimedWork
import splice.core.perf.TurnPerfTiming
import splice.core.usage.PlanLimit
import splice.core.wire.HttpStatus
import splice.core.wire.RateLimitReply
import splice.upstream.CredentialHeaders
import splice.upstream.RetryNotice
import splice.upstream.StreamRead
import splice.upstream.failure.VendorRefusal
import splice.upstream.retry.RETRY_AFTER_GIVE_UP_MS
import splice.upstream.retry.RateLimitCooldown

/** The per-post collaborators threaded through every attempt (grouped: one cohesive argument).
 *  Callers construct this and pass it to [UpstreamClient.post]. */
public data class PostContext(
    val url: String,
    val auth: RefreshableAuthProvider,
    val extraHeaders: CredentialHeaders,
    val onRetry: RetryNotice = RetryNotice {},
    val recovery: PostRecovery = PostRecovery(),
    val limits: PostLimits = PostLimits(),
    val observers: PostObservers = PostObservers(),
) {
    /** True only for the passthrough dialect, whose client understands the provider's native 429. */
    public var relayRateLimitReplies: Boolean = false

    /** Another transport already refused this exact body as too large: the Responses WebSocket peer closed the
     *  round's socket with 1009 (RFC 6455: message too big) before its first event. A 4xx for the same body is
     *  then the second refusal of the same bytes, never retried, and read as an overflow (WsSizeRefusal). */
    public var bodyRefusedAsTooLarge: Boolean = false

    /** A login from a pool, which a refusal waiting cannot fix leaves for another login before any byte reaches the
     *  client: the native wire and a translated one alike (the Oct deviation of §11). */
    internal val pooled: Boolean get() = limits.rateLimitCooldown != null

    /** A pooled login whose client understands the provider's native 429, which is handed off with no wait. */
    internal val nativePool: Boolean get() = relayRateLimitReplies && pooled

    /** Delivered only after the provider accepts the HTTP request, before consuming its stream. */
    public var upstreamAccepted: splice.upstream.StreamStart = splice.upstream.StreamStart {}

    /** Passive metadata only; a caller without an observer retains the existing transport behavior. */
    public var providerAnswerObserver: ProviderAnswerObserver = ProviderAnswerObserver { _, _ -> }

    internal fun observeQuotaRefusal(status: Int, at: Long, plan: PlanLimit?) {
        if (status == HttpStatus.TOO_MANY_REQUESTS && plan != null) providerAnswerObserver.quotaRefused(status, at)
    }

    internal fun markRetry() {
        observers.perf?.add(PerfKeys.RETRIES, 1)
    }

    internal fun markAttempt() {
        observers.perf?.add(PerfKeys.ATTEMPTS, 1)
    }

    internal fun markPostSendRetry() {
        observers.perf?.add(PerfKeys.POST_SEND_RETRIES, 1)
    }

    internal fun markHeaders() {
        observers.perf?.mark(PerfKeys.HEADERS)
    }

    internal suspend fun <T> timedAuth(block: TimedWork<T>): T =
        TurnPerfTiming.timedOr(observers.perf, PerfKeys.AUTH_MS, block)

    internal suspend fun <T> timedBackoff(block: TimedWork<T>): T =
        TurnPerfTiming.timedOr(observers.perf, PerfKeys.BACKOFF_MS, block)

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
        /** A refusal the vendor module knows (VendorRefusals): the retry loop gives up on it at once. */
        val refusal: VendorRefusal? = null,
        /** The vendor module named this status a dead credential (VendorRefusals.isAuthFailure); a 401 needs no name. */
        val vendorAuth: Boolean = false,
    ) : RetryOutcome<Nothing>() {
        /** A 429 the same login cannot outwait: a named plan window, or a pushback past [ceilingMs], the 15s ceiling
         *  unless a warm session has more of its wait left (V4-61). */
        fun waitCannotHelp(ceilingMs: Long = RETRY_AFTER_GIVE_UP_MS): Boolean =
            status == HttpStatus.TOO_MANY_REQUESTS && (planLimit != null || (retryAfterMs ?: 0L) > ceilingMs)
    }
}
