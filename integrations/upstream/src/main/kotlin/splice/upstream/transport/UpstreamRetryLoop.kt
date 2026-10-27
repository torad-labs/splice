// NEW: the retry loop's own vocabulary: one post's mutable state across attempts, and the step each attempt
// returns. Split from UpstreamClient.kt, which runs the loop; private there, internal here so the loop can read it.
// Failure-handling slice 4: the state keeps no budget of its own. Every count a retry spends is the turn's one
// RetryLedger, which RetryEngine reads, so a post and a round of the same turn spend one budget.
package splice.upstream.transport

import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.util.ElapsedClock
import splice.upstream.RoundBody
import splice.upstream.TurnRetry
import splice.upstream.retry.CredentialCooldowns
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.RetryLedger
import splice.upstream.sse.AttemptRecorder

internal enum class RetryKind { ORDINARY, POST_SEND }

/** Encodes one round's body into the request the wire sends: [UpstreamRequest.body], which keeps the bytes it wrote
 *  and the content-encoding they ride under. */
internal fun interface RequestBodyFromRound {
    operator fun invoke(round: RoundBody): RequestBody
}

/** One post's state across its attempts. [t0] is when the post began, the start of its deadline; [retry] is the turn's
 *  ledger and waits. */
internal class RetryState(var cooldown: RateLimitCooldown, val t0: Long, val retry: TurnRetry) {
    val ledger: RetryLedger get() = retry.ledger

    /** The last answer a send of this post failed with, which the post ends with when it gives up. */
    var lastErr: RetryOutcome.Failed? = null

    /** The last failure a send met before any answer when it is the newest: a transport error [lastThrown], or a
     *  stream torn before the client saw a frame [lastTear]. A post whose time runs out after it ends with that
     *  failure, as one the engine ended would. */
    var lastThrown: Throwable? = null
    var lastTear: StreamTornBeforeClient? = null

    /** Whether a send of this post failed yet, by any of the three. */
    val failedYet: Boolean get() = lastErr != null || lastThrown != null || lastTear != null

    // A retry counts only once its send goes out, past the next attempt's budget, auth and hold checks.
    var pendingRetry: RetryKind? = null

    fun markResend(ctx: PostContext) {
        val retry = pendingRetry ?: return
        pendingRetry = null
        ctx.markRetry()
        if (retry == RetryKind.POST_SEND) ctx.markPostSendRetry()
    }

    // V4-174: every SEND of this post — the ordinal the wire observer sees.
    var sent: Int = 0

    /** What is left of the turn's one wait on a warm session's login, whatever the turn's earlier posts already spent
     *  of it; 0 for a cold session. */
    fun warmWaitLeftMs(ctx: PostContext): Long = ctx.limits.warmWait?.leftMs() ?: 0L

    /** V4-174: one send's recorder, built only when the context carries an observer. Each send is one of the turn's
     *  attempts too, which the ledger counts on the login the send goes out on. */
    fun recorderFor(ctx: PostContext, body: RequestBody, clock: ElapsedClock): AttemptRecorder? {
        sent += 1
        ledger.sent()
        return ctx.observers.wire?.let { AttemptRecorder(sent, ctx.url, body.json, body.encoding, clock) }
    }

    /** V4-174: the attempt is reported once, after it ended either way — a thrown transport
     *  failure and a classified HTTP failure are both endings the trace must show. */
    fun report(ctx: PostContext, recorder: AttemptRecorder?, failure: Throwable?) {
        if (recorder != null) ctx.observers.wire?.attempted(recorder.finish(failure))
    }

    /** Captures one attempt's auth once, so its hold identity and wire headers cannot diverge. The ledger's login is
     *  the login provider itself, which keeps its identity across the token renewals it makes, so a renewal spent on
     *  it stays spent; another login of the pool is another provider. */
    suspend fun credentials(
        ctx: PostContext,
        credentials: Credentials,
        cooldowns: CredentialCooldowns,
        fallback: RateLimitCooldown,
    ): AttemptCredentials {
        // Preserve the request-preparation origin: header resolution belongs to POST wait, not prior round work.
        val postedAtMs = ctx.observers.perf?.elapsedMs()
        // The vendor routes each attempt, so a retry sent after its route was left rides the new one (VendorRoute).
        val headers = ctx.extraHeaders(credentials) + ctx.recovery.route?.sending().orEmpty()
        val selected = ctx.limits.rateLimitCooldown ?: cooldowns.forHeaders(
            CredentialKey.headers(credentials, headers),
            (credentials as? Credentials.ApiKey)?.header,
        ) ?: fallback
        cooldown = selected
        ledger.onLogin(LOGIN_PREFIX + System.identityHashCode(ctx.auth).toString(LOGIN_RADIX))
        return AttemptCredentials(credentials, headers, postedAtMs, selected)
    }

    /** The body this attempt sends: [body] as it was built, or the copy the vendor's route made of it for the place
     *  [credentials] just chose (VendorRoute.body), encoded by [encode]. Each attempt asks with the body as built, so
     *  what it sends never depends on what an earlier attempt sent. */
    fun routedBody(ctx: PostContext, body: RequestBody, encode: RequestBodyFromRound): RequestBody =
        ctx.recovery.route?.body(body.body)?.let(encode::invoke) ?: body
}

internal sealed class LoopStep<out T> {
    data class Done<T>(val result: UpstreamPost<T>) : LoopStep<T>()
    data class Amend(val bodyJson: String) : LoopStep<Nothing>()
    data object Continue : LoopStep<Nothing>()

    /** The whole-turn wait budget was already gone: no attempt was made and no upstream
     *  response exists to classify. [UpstreamClient.post] lifts this to [UpstreamPost.TurnWaitExhausted]. */
    data object TurnWaitExhausted : LoopStep<Nothing>()
}

/** How the ledger names a login: the identity of its provider, never anything its credential holds. */
private const val LOGIN_PREFIX = "login-"
private const val LOGIN_RADIX = 16 // why: hex, the way an identity hash is printed, so the name stays short
