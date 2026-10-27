// NEW: failure-handling slice 3 (2026-10-26). Renamed from MuseRefusals, which rode on the login provider: what Muse
// says differently from the Responses standard, which claude-muse's Replies reads before the standard does
// (final-design.md section 12). MUSE_CONTINUES_REASONING_ONLY stays what it was, beside these rows.
//
// Deleted with MuseRefusals: its 403 plan rule, since the standard reads a 403 as Forbidden anyway. What is kept of it
// is the reading of a plan limit at a status that is NOT 403, which the standard would read as a dead login.
package splice.provider.muse

import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LoginOwner
import splice.core.wire.HttpStatus
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedReply
import splice.upstream.failure.ProviderDeviation
import splice.upstream.failure.ReplyFacts
import java.time.Instant

/**
 * Muse's two answers of its own, and its key mint's verdicts.
 *
 * A 429's stated reset is SOFT. The 429 of Oct 25, 10:16:47 PM CT named a reset 2 h 8 min out and carried Retry-After
 * 7,728,000 ms, and four of the fourteen requests sent over the next seven minutes came back 200, two of them 15 s and
 * 20 s after a 429 naming that reset (audit-verdicts.md:186-197). So the instant is Muse's word about its window, not
 * a closed door: the limit holds with probes, and a probe may be served well before it.
 *
 * A body naming the account's plan limit is Muse's verdict on the plan, whatever status spells it. Muse sent it as a
 * 403, which the standard already reads as Forbidden; a 401 spelling of it would read as a dead login and spend a
 * refresh on a plan a renewed login meets again, which the mint's own hold already refuses (MuseAuthProvider's
 * allowRefreshAfterFailure, kept).
 *
 * Its key mint refuses a login with a reason of its own ([mintRefusal]). Before slice 3 a mint that granted nothing
 * returned no credentials, so the turn ended as NoLogin and told the person to sign in, even where the account was
 * signed in and its subscription was inactive (MuseMintVerdict.kt:15-37).
 */
public object MuseReplies : ProviderDeviation {
    private val oauth = MuseOAuth()

    override fun http(reply: FailedReply): FailureCause.Provider? = when {
        reply.status == HttpStatus.TOO_MANY_REQUESTS ->
            FailureCause.Provider.Limited(softLift(reply), window = null, ErrorMessage.said(reply.body))
        oauth.isPlanTierRejection(reply.body) -> FailureCause.Provider.Forbidden(ErrorMessage.said(reply.body))
        else -> null
    }

    /** What a key mint that granted nothing refuses this login for, at [nowMs]; null for a mint that granted a key.
     *  Each verdict's hold is MuseMintHolds', and the cause says the same thing to the person: an inactive
     *  subscription says to renew it, a rate-limited mint is a soft limit until its hold ends, a denied mint is
     *  forbidden, and an account token the mint rejected is the rejected login it is. */
    internal fun mintRefusal(attempt: MuseMintAttempt, nowMs: Long): FailureCause.Provider? = when (attempt) {
        is MuseMintAttempt.Granted -> null
        MuseMintAttempt.InvalidAccountToken -> FailureCause.Provider.AuthRejected(LoginOwner.SPLICE)
        is MuseMintAttempt.SubscriptionRequired ->
            FailureCause.Provider.NeedsPayment(Lift.Unstated, subscription = true)
        is MuseMintAttempt.RateLimited -> FailureCause.Provider.Limited(
            Lift.At(Instant.ofEpochMilli(nowMs + heldMs(attempt.retryAfterMs)), firm = false, LiftSource.BODY),
            window = null,
        )
        is MuseMintAttempt.Denied -> FailureCause.Provider.Forbidden()
    }

    /** The reset the answer states, held softly: the body's own first, then its Retry-After, then none. */
    private fun softLift(reply: FailedReply): Lift =
        ReplyFacts.statedReset(reply)?.let { Lift.At(it, firm = false, LiftSource.BODY) }
            ?: ReplyFacts.retryAfterMs(reply)?.let { ReplyFacts.liftAfter(reply, it, LiftSource.RETRY_AFTER, false) }
            ?: Lift.Unstated

    /** How long a rate-limited mint holds, exactly as [MuseMintVerdict] records it. */
    private fun heldMs(retryAfterMs: Long?): Long =
        (retryAfterMs ?: DEFAULT_RATE_HOLD_MS).coerceIn(DEFAULT_RATE_HOLD_MS, MAX_MINT_HOLD_MS)
}

/** Muse completes rounds that hold one encrypted reasoning item and nothing else
 *  (status=completed items=[reasoning(summary=0,enc=~1400)]): 418 of 3,538 turns on 2026-10-13, in runs of up to 25 in
 *  one session. The same bytes give the same empty, so the identical re-send healed 39 of 451; carrying on from that
 *  reasoning with a request for the answer changes the input. Muse accepts the shape: its mid-stream re-anchor already
 *  sends reasoning plus a commentary marker and was answered. The app sets the Responses dialect's
 *  continue_reasoning_only quirk from this, so the fact stays in this module. */
public const val MUSE_CONTINUES_REASONING_ONLY: Boolean = true
