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
import splice.core.auth.LoginAnswer
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.perf.PerfKeys
import splice.core.perf.TimedWork
import splice.core.perf.TurnPerfTiming
import splice.core.usage.PlanLimit
import splice.core.wire.HttpStatus
import splice.core.wire.NativeReply
import splice.upstream.CredentialHeaders
import splice.upstream.RetryNotice
import splice.upstream.StreamRead
import splice.upstream.failure.Replies
import splice.upstream.retry.RETRY_AFTER_GIVE_UP_MS
import splice.upstream.retry.RateLimitCooldown

/** The per-post collaborators threaded through every attempt (grouped: one cohesive argument).
 *  Callers construct this and pass it to [UpstreamClient.post]. */
public data class PostContext(
    val url: String,
    val auth: RefreshableAuthProvider,
    val extraHeaders: CredentialHeaders,
    /** How this head reads its own failures (Provider.replies): the one reading of every reply this post gets, with
     *  no default, so a head whose reading never reached the transport cannot be read by another head's
     *  (failure-handling slice 3, final-design.md section 7). */
    val replies: Replies,
    val recovery: PostRecovery = PostRecovery(),
    val limits: PostLimits = PostLimits(),
    val observers: PostObservers = PostObservers(),
) {
    /** Why each attempt is retried or given up, as the post's observers hear it. */
    public val onRetry: RetryNotice get() = observers.onRetry

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

    /** Failure-handling slice 3: the cause says a limit, where the status used to (the 429 V4-73 rewrote a quota
     *  exhaustion into). The observer still hears the 429 it records, which is the answer's own wire shape. */
    internal fun observeQuotaRefusal(failed: RetryOutcome.Failed, at: Long) {
        if (failed.planLimit != null && failed.limited) {
            providerAnswerObserver.quotaRefused(HttpStatus.TOO_MANY_REQUESTS, at)
        }
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

    /** What this attempt's login lookup answers: the credentials to send; none, which ends the call with
     *  [UpstreamAuthMissing] for the head's NoLogin; or the provider's refusal to grant one, which ends it with the
     *  refusal's own cause (failure-handling slice 3). */
    internal suspend fun requireAuth(): LoginAnswer = timedAuth { auth.answer() }
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

    /** Failure-handling slice 3: ONE reading, done at the boundary by the head's own reader, and carried from here
     *  on. [cause] is what the reply was; [evidence] what it left for the journal, the trace and the perf row (its
     *  status, its body as it came, the pushback its Retry-After asked for, which today's budgets still read).
     *  Nothing past this point reads a status or a body to decide anything. */
    data class Failed(
        val cause: FailureCause.Provider,
        val evidence: FailureEvidence,
        /** V4-233: the plan window this reply named spent, read where its headers were still in hand. Null for every
         *  reply that names none. */
        val planLimit: PlanLimit? = null,
        /** The provider's own 429, kept for a client that reads it natively. */
        val native: NativeReply? = null,
        /** This provider names the reply a dead credential though its status is not 401 (ProviderDeviation): xAI's
         *  expiry 403. One refresh is spent on it, as on a 401. */
        val deadCredential: Boolean = false,
    ) : RetryOutcome<Nothing>() {
        /** The two readings today's 429 budgets are about: a rate limit, and the payment a provider asks for, which
         *  V4-73 made every layer see as the 429 it dressed its quota exhaustion in. */
        val limited: Boolean
            get() = cause is FailureCause.Provider.Limited || cause is FailureCause.Provider.NeedsPayment

        /** A limit the same login cannot outwait: a named plan window, or a pushback past [ceilingMs], the 15s
         *  ceiling unless a warm session has more of its wait left (V4-61). */
        fun waitCannotHelp(ceilingMs: Long = RETRY_AFTER_GIVE_UP_MS): Boolean =
            limited && (planLimit != null || (evidence.retryAfterMs ?: 0L) > ceilingMs)
    }
}
