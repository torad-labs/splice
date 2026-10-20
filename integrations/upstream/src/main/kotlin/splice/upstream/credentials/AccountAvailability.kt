// NEW: Pure per-account availability and quota math, split out of AccountPool.kt: it takes a PoolAccount and the time and touches no pool state.
package splice.upstream.credentials

import splice.core.usage.QuotaWindow
import splice.upstream.retry.MAX_RATE_LIMIT_COOLDOWN_MS
import java.util.concurrent.TimeUnit

// why: a quota window reports usedPercent on a 0-100 scale, so 100.0 is an exhausted window.
private const val FULLY_USED = 100.0

/** The longest a warm session waits, in all, out rate limits on its own login before it moves (Marcos, Oct 19:
 *  "switching mid session when the cache is hot can cost a lot"). A move rebuilds the conversation's whole cache on the
 *  next login, which costs far more than two minutes of waiting. A plan limit or a needed sign-in moves it at once.
 *
 *  It IS the ceiling every rate-limit hold is clamped to ([MAX_RATE_LIMIT_COOLDOWN_MS]), by reference and not by an
 *  equal number: a warm session can wait out any such hold, so a hold alone never disqualifies a stay. Lowering this
 *  below that ceiling would make a long hold move the session, and [Warm.stays] would then need that check back. */
internal const val WARM_LOGIN_WAIT_MS: Long = MAX_RATE_LIMIT_COOLDOWN_MS

/** Pure per-account availability/quota math — every function here takes the [PoolAccount] (and
 *  [at]) it needs and touches no [AccountPool] state, so V4-132 split it out rather than push
 *  [AccountPool] past detekt's 15-function class ceiling (TooManyFunctions) with its new
 *  pin/unpin/pinned/nextTargetLabel surface. Behaviour is byte-identical to the methods it
 *  replaces — a relocation, not a rewrite. */
internal object AccountAvailability {
    /** Free to serve: selectable, and not held on a plan window the provider named spent. Failover within one
     *  provider (operator ruling, Oct 3): a held login stays held until the reset it named, so the next turn of the
     *  same command goes to the next login, and a restart restores the hold with the rest of the provider's word. */
    fun available(account: PoolAccount, at: Long): Boolean =
        selectable(account, at) && !account.quotaHeld && account.cooldown.planHold.live() == null &&
            (account.cooldown.rateLimitReply == null || account.cooldown.remainingMs() <= 0L)

    /** The logins held on their plan that could otherwise serve, nearest reset first; ties keep [order]. */
    fun nearestHeld(order: List<PoolAccount>, at: Long): List<PoolAccount> =
        order.filter { account ->
            val held = account.cooldown.planHold.live() != null ||
                (account.cooldown.rateLimitReply != null && account.cooldown.remainingMs() > 0L)
            held && account.credentialStatus(at).selectable && account.cooldown.unavailableForMs() <= 0L
        }.sortedBy { maxOf(it.cooldown.providerUnavailableForMs(), it.cooldown.remainingMs()) }

    /** Held only by the provider's short rate limit, its plan named spent by neither a hold nor a full reading: admission
     *  waits that hold out on the same login (CredentialHoldAdmission), so it is no exhausted login. A pool flipped from
     *  such a login to one held on its seven-day window until the next day, and failed turns as every login exhausted
     *  while the first had plan room (claude-splice, Oct 13, 6:42 PM CT). */
    fun planRoom(account: PoolAccount, at: Long): Boolean =
        !account.quotaHeld && account.cooldown.planHold.live() == null && !fullReading(account, at)

    private fun selectable(account: PoolAccount, at: Long): Boolean =
        account.credentialStatus(at).selectable && account.cooldown.unavailableForMs() <= 0L

    /** The login a turn takes from [order], then the one it moves to when that one runs out: the first free login, or
     *  when every one is held, the one whose hold ends soonest. At most two; empty when none could serve. A warm
     *  session's [stay] comes first, as it does when the turn is selected ([Warm.stays]). */
    fun upNext(order: List<PoolAccount>, at: Long, stay: PoolAccount? = null): List<PoolAccount> {
        val pick = { from: List<PoolAccount> ->
            preferredFree(from, at).firstOrNull() ?: nearestHeld(from, at).firstOrNull()
        }
        val next = stay ?: pick(order) ?: return emptyList()
        return listOfNotNull(next, pick(order.filterNot { it.label == next.label }))
    }

    /** Full readings guide spending, never synthesize a refusal that the provider did not make. */
    fun preferredFree(order: List<PoolAccount>, at: Long): List<PoolAccount> {
        val free = order.filter { available(it, at) }
        return free.filterNot { fullReading(it, at) }.ifEmpty { free }
    }

    fun fullReading(account: PoolAccount, at: Long): Boolean = account.quotaSnapshot?.let {
        exhausted(it.fiveHour, at) || exhausted(it.sevenDay, at)
    } == true

    /** Why [account] stopped serving: its credential first, then the plan the provider named spent. A rejected or
     *  signed-out login needs the person; an expired token its own command renews says only that it expired, never
     *  that a sign-in is needed (the field audit, Oct 13: 3,743 such lines after one token expired). */
    fun limitReason(account: PoolAccount, at: Long): String {
        val plan = account.cooldown.planHold.live()
        val credential = credentialReason(account.credentialStatus(at))
        return when {
            credential != null -> credential
            plan != null -> AccountSwitchReason.planLimit(plan.windowWords)
            account.cooldown.rateLimitReply != null && account.cooldown.remainingMs() > 0L ->
                AccountSwitchReason.PROVIDER_LIMIT
            account.cooldown.unavailableForMs() > 0L -> AccountSwitchReason.WAIT_BUDGET
            else -> AccountSwitchReason.ACCOUNT_UNAVAILABLE_REASON
        }
    }

    /** The credential's half of [limitReason], or null when the credential can send. */
    private fun credentialReason(credential: AccountCredentialEligibility.Status): String? = when {
        credential.excludedUntilEpochMillis != null -> AccountSwitchReason.SIGN_IN_NEEDED
        credential.renewable -> AccountSwitchReason.TOKEN_EXPIRED
        !credential.credentialPresent -> AccountSwitchReason.SIGN_IN_NEEDED
        else -> null
    }

    fun resetOrder(at: Long): Comparator<PoolAccount> =
        compareBy<PoolAccount> { account ->
            val quota = account.quotaSnapshot
            rankingWindow(account, quota?.sevenDay, at) == null && rankingWindow(account, quota?.fiveHour, at) == null
        }.thenBy { !available(it, at) }
            .thenBy { rankingWindow(it, it.quotaSnapshot?.sevenDay, at)?.resetsAt ?: Long.MAX_VALUE }
            .thenBy { rankingWindow(it, it.quotaSnapshot?.fiveHour, at)?.resetsAt ?: Long.MAX_VALUE }
            .thenByDescending { it.primary }
            .thenBy { it.label }

    private fun rankingWindow(account: PoolAccount, window: QuotaWindow?, at: Long): QuotaWindow? =
        window?.takeIf {
            val reset = it.resetsAt
            available(account, at) && (reset == null || reset > TimeUnit.MILLISECONDS.toSeconds(at))
        }

    fun exhausted(window: QuotaWindow?, at: Long): Boolean {
        if (window == null || window.usedPercent < FULLY_USED) return false
        val reset = window.resetsAt ?: return false
        return TimeUnit.SECONDS.toMillis(reset) > at
    }

    /** Whether a session stays on its login rather than move: it was served there within [WARM_CACHE_MS], and the login
     *  only needs a short wait before it serves again. */
    object Warm {
        /** Claude Code's subscriber requests ask for a one-hour prompt cache, so a session served within the hour may
         *  still hold one there; past it, a move costs no cache. */
        private const val WARM_CACHE_MS = 3_600_000L

        fun recent(servedAt: Long?, at: Long): Boolean = servedAt != null && at - servedAt in 0 until WARM_CACHE_MS

        /** [account] when the session keeps it: served within the hour, no pin or changed caller login elsewhere in
         *  [preference], and the login serves again once a short wait ends (signed in and with plan room). A rate-limit
         *  hold needs no check of its own: it is clamped to [WARM_LOGIN_WAIT_MS] by construction, so the wait always
         *  fits. The reset a 429 body names is left out: it is often the window's reset stamped on a burst limit that
         *  clears in seconds (RateLimitCooldown, V4-61). */
        fun stays(account: PoolAccount, servedAt: Long?, preference: List<PoolAccount>, at: Long): PoolAccount? {
            val signedIn = account.credentialStatus(at).selectable && account.cooldown.unavailableForMs() <= 0L
            val chosen = preference.all { it.label == account.label }
            return account.takeIf { recent(servedAt, at) && chosen && signedIn && planRoom(account, at) }
        }
    }

    fun earliestReset(accounts: List<PoolAccount>, at: Long): Long? =
        accounts.mapNotNull { blockedUntil(it, at) }.minOrNull()

    fun blockedUntil(account: PoolAccount, at: Long): Long? {
        val credential = account.credentialStatus(at)
        if (!credential.credentialPresent) return null
        val snapshot = account.quotaSnapshot
        val blocked = listOfNotNull(snapshot?.fiveHour, snapshot?.sevenDay).filter { exhausted(it, at) }
        val quotaReset = blocked.takeIf { account.quotaHeld }?.mapNotNull(QuotaWindow::resetsAt)?.maxOrNull()
        // A short provider hold blocks selection while it runs ([available]), so it is a reset too: without it a pool
        // whose only free login waits seconds read the next plan reset, a day away, as its earliest.
        val shortHold = account.cooldown.remainingMs().takeIf { account.cooldown.rateLimitReply != null } ?: 0L
        val remaining = maxOf(account.cooldown.providerUnavailableForMs(), shortHold)
        // Vendors stamp a reset in epoch seconds while the pool's clock runs in milliseconds; a part second rounds up.
        val wholeSeconds = TimeUnit.MILLISECONDS.toSeconds(remaining)
        val extraSecond = if (TimeUnit.SECONDS.toMillis(wholeSeconds) == remaining) 0L else 1L
        val cooldownSeconds = wholeSeconds + extraSecond
        val epochSeconds = TimeUnit.MILLISECONDS.toSeconds(at)
        val cooldownReset = epochSeconds + cooldownSeconds
        val authReset = credential.excludedUntilEpochMillis?.let { millis ->
            val seconds = TimeUnit.MILLISECONDS.toSeconds(millis)
            val rounded = TimeUnit.SECONDS.toMillis(seconds) < millis && seconds < Long.MAX_VALUE
            seconds + if (rounded) 1L else 0L
        }
        return listOfNotNull(quotaReset, cooldownReset.takeIf { remaining > 0L }, authReset).maxOrNull()
    }
}
