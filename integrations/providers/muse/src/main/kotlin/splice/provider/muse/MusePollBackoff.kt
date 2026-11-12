// NEW: audit, Nov 9, 2026 — the usage poller's own backoff from a key mint that keeps refusing it.
//
// The poller reads Muse's usage off a key mint. Once a poll's refusal stopped holding turns (one refused poll held
// every turn for up to an hour though the stored key worked), nothing slowed the poller either: it asked the mint every
// poll while the mint kept refusing, and an inactive subscription no longer reached the account's description until a
// turn's own renewal found it. This is the poller's alone. It never refuses a turn and never holds a turn's renewal,
// which answer to MuseMintHolds; it only spaces the poll's own mints and says what the last one found.
package splice.provider.muse

import splice.core.auth.CredentialFileIdentity
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.time.Duration

/** Doublings of [DEFAULT_RATE_HOLD_MS] before the wait reaches [MAX_MINT_HOLD_MS]: 1, 2, 4 ... 64 minutes, capped. */
private const val MAX_POLL_DOUBLINGS = 6

internal class MusePollBackoff(private val clock: WallClock, private val log: LogSink) {
    /** The poll's backoff for one credential revision: refused [refusals] times in a row, next asked at [untilMs]. */
    private data class Backoff(
        val identity: CredentialFileIdentity?,
        val accessToken: String?,
        val untilMs: Long,
        val refusals: Int,
        val inactive: Boolean,
        val actionUrl: String?,
    ) {
        /** Keyed to a token, so it carries one; the rest is what makes a backoff readable and none of it is secret. */
        override fun toString(): String =
            "Backoff(identity=$identity, accessToken=${if (accessToken == null) "null" else "<redacted>"}, " +
                "untilMs=$untilMs, refusals=$refusals, inactive=$inactive, actionUrl=$actionUrl)"
    }

    private val oauth = MuseOAuth()

    @Volatile
    private var backoff: Backoff? = null

    /** Whether the poll waits before asking the mint again for [snapshot]'s login. */
    fun waits(snapshot: MuseCredentialSnapshot): Boolean = current(snapshot)?.let { clock() < it.untilMs } ?: false

    /** What the poll's mints found that the account's description shows: an inactive subscription, and where to renew
     *  it. Shown, never acted on: the stored key keeps serving every turn. */
    fun description(snapshot: MuseCredentialSnapshot): Map<String, String> {
        val found = current(snapshot)?.takeIf { it.inactive } ?: return emptyMap()
        return buildMap {
            put("subscription", "inactive")
            found.actionUrl?.let { put("action_url", it) }
        }
    }

    /** A poll's mint that granted no key: the wait doubles with each refusal in a row, from a minute to an hour, and
     *  never runs shorter than a Retry-After the mint gave. A definite "subscription inactive" is kept for the
     *  description until a mint grants a key or the login changes; a refusal that says nothing about the subscription
     *  keeps what the last one found. */
    fun refused(snapshot: MuseCredentialSnapshot, attempt: MuseMintAttempt) {
        val prior = current(snapshot)
        val refusals = (prior?.refusals ?: 0) + 1
        val doubled = DEFAULT_RATE_HOLD_MS shl (refusals - 1).coerceAtMost(MAX_POLL_DOUBLINGS)
        val asked = (attempt as? MuseMintAttempt.RateLimited)?.retryAfterMs ?: 0L
        val waitMs = maxOf(doubled, asked).coerceAtMost(MAX_MINT_HOLD_MS)
        val inactive = attempt as? MuseMintAttempt.SubscriptionRequired
        backoff = Backoff(
            identity = snapshot.identity,
            accessToken = snapshot.accessToken,
            untilMs = clock() + waitMs,
            refusals = refusals,
            inactive = inactive != null || prior?.inactive == true,
            actionUrl = inactive?.let { oauth.safeActionOrigin(it.actionUrl) } ?: prior?.actionUrl,
        )
        log(
            "[muse-auth] usage poll mint answered ${attempt::class.simpleName}; the poll asks again in " +
                "${Duration.ofMillis(waitMs).toSeconds()}s, no turn is held and the stored key serves",
        )
    }

    /** A mint granted a key, the poll's own or a turn's: the account answers, so the poll asks at its own pace. */
    fun clear() {
        backoff = null
    }

    private fun current(snapshot: MuseCredentialSnapshot): Backoff? =
        backoff?.takeIf { snapshot.isRevision(it.identity, it.accessToken) }
}
