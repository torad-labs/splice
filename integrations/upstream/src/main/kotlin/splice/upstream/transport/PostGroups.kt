// NEW: the groups a PostContext carries (recovery, limits, observers) and the three seams they hold, moved verbatim
// from UpstreamAttempt.kt, which keeps the context itself and the attempt's outcome. Same package: no call site changes.
package splice.upstream.transport

import splice.core.perf.TurnPerf
import splice.upstream.BodyAmendment
import splice.upstream.ClientFrameEmitted
import splice.upstream.RetryNotice
import splice.upstream.VendorRoute
import splice.upstream.retry.RETRY_AFTER_GIVE_UP_MS
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.WarmLoginWait
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

/** What a failed send of a post may do next: whether the client has already been shown a frame, which forbids a
 *  reissue, how the request body is amended before a retry, and where the vendor routes the retry. The defaults
 *  reissue freely, amend nothing and route nothing. */
public data class PostRecovery(
    val clientFrameEmitted: ClientFrameEmitted = ClientFrameEmitted { true },
    val amendBodyOnFailure: BodyAmendment = BodyAmendment { _, _, _ -> null },
    /** The turn's route, asked for each attempt's headers and told when the request went out whole, when its
     *  response's headers came, and every answer that is not a stream. */
    val route: VendorRoute? = null,
)

/** What bounds a post beyond its own retry rules: the selected account's cooldown and the outer turn's wait. */
public data class PostLimits(
    /** Selected account's cooldown; null preserves the client's legacy single-account cooldown. */
    val rateLimitCooldown: RateLimitCooldown? = null,
    /** Null preserves the legacy per-post deadline for callers without an outer turn budget. */
    val remainingTurnWait: RemainingTurnWait? = null,
    /** A warm turn's total wait for rate limits on its own login before it moves (Marcos, Oct 19), one budget shared
     *  with admission and with every other post of the turn; null for a cold turn, which waits at most
     *  [RETRY_AFTER_GIVE_UP_MS] per 429 as before. */
    val warmWait: WarmLoginWait? = null,
)

/** What listens to a post without changing it: the turn's timing row, the wire recorder, the refresh observer and
 *  the retry notice. */
public data class PostObservers(
    val perf: TurnPerf? = null,
    /** V4-174: hears every send of this post after it ends (headers redacted, body exact). Null —
     *  the default and every head that did not opt in — records nothing and allocates nothing. */
    val wire: WireObserver? = null,
    val authRefreshObserver: AuthRefreshObserver = AuthRefreshObserver {},
    /** Hears why each attempt is retried or given up, in the log's words. It moved here from the context itself when
     *  the head's reading joined it (failure-handling slice 3), keeping the context at the constructor-arity wall. */
    val onRetry: RetryNotice = RetryNotice {},
)
