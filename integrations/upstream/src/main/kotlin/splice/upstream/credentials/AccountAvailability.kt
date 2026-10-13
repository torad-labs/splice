// NEW: Pure per-account availability and quota math, split out of AccountPool.kt: it takes a PoolAccount and the time and touches no pool state.
package splice.upstream.credentials

import splice.core.usage.QuotaWindow
import java.util.concurrent.TimeUnit

// why: a quota window reports usedPercent on a 0-100 scale, so 100.0 is an exhausted window.
private const val FULLY_USED = 100.0

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

    private fun selectable(account: PoolAccount, at: Long): Boolean =
        account.credentialStatus(at).selectable && account.cooldown.unavailableForMs() <= 0L

    /** The login a turn takes from [order], then the one it moves to when that one runs out: the first free login, or
     *  when every one is held, the one whose hold ends soonest. At most two; empty when none could serve. */
    fun upNext(order: List<PoolAccount>, at: Long): List<PoolAccount> {
        val pick = { from: List<PoolAccount> ->
            preferredFree(from, at).firstOrNull() ?: nearestHeld(from, at).firstOrNull()
        }
        val next = pick(order) ?: return emptyList()
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

    fun earliestReset(accounts: List<PoolAccount>, at: Long): Long? =
        accounts.mapNotNull { blockedUntil(it, at) }.minOrNull()

    fun blockedUntil(account: PoolAccount, at: Long): Long? {
        val credential = account.credentialStatus(at)
        if (!credential.credentialPresent) return null
        val snapshot = account.quotaSnapshot
        val blocked = listOfNotNull(snapshot?.fiveHour, snapshot?.sevenDay).filter { exhausted(it, at) }
        val quotaReset = blocked.takeIf { account.quotaHeld }?.mapNotNull(QuotaWindow::resetsAt)?.maxOrNull()
        val remaining = account.cooldown.providerUnavailableForMs()
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
