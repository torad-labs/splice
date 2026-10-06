// NEW: v0.4.0 FEATURES.md §11 — sticky per-session selection across OAuth accounts.
package splice.upstream.credentials

import splice.core.auth.ClientAuthProvider
import splice.core.usage.QuotaWindow
import splice.core.util.LruSizing
import splice.core.util.WallClock
import java.util.concurrent.atomic.AtomicReference

private const val FULLY_USED = 100.0
private const val MS_PER_SECOND = 1_000L
private const val MAX_TRACKED_SESSIONS = 4_096

/** The answer [AccountPool.select] returns: an account was chosen for the turn, or every account is
 *  exhausted and the turn is refused before admission. The exhaustion is a VALUE on the return type,
 *  never a thrown exception (kt-no-exception-as-outcome): the compiler then checks the caller's
 *  `when`, so a new selection answer cannot slip past an unexhaustive caller the way a thrown
 *  refusal slipped past every `catch (e: AllAccountsExhausted)` in the tree.
 *
 *  IT LIVES HERE, with its producer, rather than in AccountSelection.kt where V4-99 first declared
 *  it. That file had reached 11 declared types and a concentration ratio of 3.11 — band HIGH, a god
 *  file by the campaign's own census — and the split the ratchet points at is by CONCEPT, not by
 *  line count. The answer to `select` belongs beside `select`; the file named for the account
 *  selection then keeps the selection ITSELF. Same package, so this is a relocation: no call site
 *  changes, no import changes, no behaviour changes. */
public sealed class Selection {
    /** An account was chosen; [account] is the immutable per-turn choice. */
    public class Chosen(public val account: AccountSelection) : Selection()

    /** No account could start the turn; [earliestResetEpochSeconds] is the soonest any account
     *  resets, null when no account reported a reset. */
    public class Exhausted(public val earliestResetEpochSeconds: Long?) : Selection() {
        public val message: String get() = AccountResetText.exhausted(earliestResetEpochSeconds)
    }
}

/** A head-local OAuth account pool. Each selection owns one immutable login choice and its probe lease. */
public class AccountPool(
    accounts: List<PoolAccount>,
    private val now: WallClock,
) {
    private class AccountMembership(accounts: List<PoolAccount>) {
        val accounts: List<PoolAccount> = accounts.toList().also {
            require(it.count(PoolAccount::primary) == 1) { "account pool must have exactly one primary" }
        }
        val byLabel: Map<String, PoolAccount> = this.accounts.associateBy(PoolAccount::label)
        val primary: PoolAccount = this.accounts.single(PoolAccount::primary)

        init {
            require(this.accounts.isNotEmpty()) { "account pool must not be empty" }
            require(byLabel.size == this.accounts.size) { "account labels must be unique" }
        }
    }

    private val membership = AtomicReference(AccountMembership(accounts))

    /** Publishes membership only. Existing session choices, order, cooldown objects and leased turns survive. */
    public var members: List<PoolAccount>
        get() = membership.get().accounts
        set(accounts) {
            membership.set(AccountMembership(accounts))
        }

    /** A forwarded caller alone is the legacy path, not a choice between stored logins. */
    public val active: Boolean
        get() = membership.get().let { it.accounts.size > 1 || it.primary.auth !is ClientAuthProvider }

    // Access order keeps active sessions sticky without retaining every session the daemon ever saw.
    // Reads reorder the map too, so selection, views and reset share its monitor.
    private val sessions = LinkedHashMap<String, SessionAccount>(
        LruSizing.INITIAL_CAPACITY,
        LruSizing.LOAD_FACTOR,
        true,
    )
    private val headLastSwitch = AtomicReference<AccountSwitch?>(null)
    private val statelessLock = Any()
    private var statelessPrevious: SessionAccount? = null

    // V4-132: the REAL pin behind POST /api/auth/{head}/switch. Before this, `select` was
    // policy-only (FEATURES.md 4.5 "Manual switch"): even a session sitting on a deliberately
    // chosen backup fell back to primary on its very next turn, because [choose] always tried
    // primary first when the session's previous account was not already primary. A pin is tried
    // FIRST, ahead of primary — [candidates] below is the one order [choose] and [nextTargetLabel]
    // both walk, so a pin changes both the same way. An unavailable pin falls through to the same
    // policy as before (NEVER-BELOW-STATUS-QUO): pinning never wedges a head that would otherwise
    // still be serving turns on its own.
    private val pinnedLabel = AtomicReference<String?>(null)
    private val orderedLabels = AtomicReference<List<String>>(emptyList())

    /** Operator priority, retained across runtime resets. Empty selects sticky, soonest-reset mode. */
    public var order: List<String>
        get() = orderedLabels.get().filter(membership.get().byLabel::containsKey)
        set(labels) {
            require(labels.distinct().size == labels.size) { "account order contains duplicate labels" }
            require(labels.all(membership.get().byLabel::containsKey)) { "account order names an unknown account" }
            orderedLabels.set(java.util.List.copyOf(labels))
        }

    /** The exact candidate policy, including an explicit runtime pin and fallback accounts. */
    public fun effectiveOrder(): List<String> {
        val current = membership.get()
        current.accounts.forEach { it.refreshCredentialEvidence() }
        return candidates(null, current).map(PoolAccount::label)
    }

    /** Chooses before acceptance. Null sessions re-evaluate policy without becoming sticky.
     *  Nonempty [excluded] skips refused logins and permits only a free login, never a held fallback.
     *  Returns [Selection.Chosen] with an immutable choice, or [Selection.Exhausted] as a refusal value. */
    public fun select(sessionId: String?, excluded: Set<String> = emptySet()): Selection {
        require(sessionId == null || sessionId.isNotBlank()) { "session id must not be blank" }
        val at = now()
        // Credential evidence is read (and hashed) OUTSIDE the sticky-session monitor: the lock only
        // keeps the LinkedHashMap consistent, and holding it across a filesystem round-trip makes its
        // contention window the disk's latency. Each account caches the read behind a short TTL, so
        // the in-monitor selection below reads the cache, never the credential file.
        val current = membership.get()
        current.accounts.forEach { it.refreshCredentialEvidence() }
        if (sessionId == null) {
            return synchronized(statelessLock) {
                val chosen = selected(statelessPrevious, at, sticky = false, excluded, current)
                chosen.second?.let { statelessPrevious = it }
                chosen.first
            }
        }
        return synchronized(sessions) {
            val chosen = selected(sessions[sessionId], at, sticky = true, excluded, current)
            chosen.second?.let { session ->
                sessions[sessionId] = session
                if (sessions.size > MAX_TRACKED_SESSIONS) sessions.remove(sessions.keys.first())
            }
            chosen.first
        }
    }

    private fun selected(
        previous: SessionAccount?,
        at: Long,
        sticky: Boolean,
        excluded: Set<String>,
        current: AccountMembership,
    ): Pair<Selection, SessionAccount?> {
        val chosen = choose(if (sticky) previous?.label else null, at, excluded, current)
            ?: return Selection.Exhausted(AccountAvailability.earliestReset(current.accounts, at)) to null
        // A new session starts relative to primary even when its credential is missing: choosing
        // a backup is cache-cold on that first turn and updates the head-wide last-switch notice.
        val prior = previous ?: SessionAccount(current.primary.label, null)
        val moved = prior.takeIf { it.label != chosen.account.label }?.let {
            AccountSwitch(it.label, chosen.account.label, switchReason(it.label, chosen.account, at, current), at)
        }
        moved?.let(headLastSwitch::set)
        val selection = Selection.Chosen(AccountSelection(chosen.account, moved, chosen.lease))
        val session = SessionAccount(chosen.account.label, moved ?: previous?.lastSwitch)
        return selection to session
    }

    /** Safe state for operator surfaces; null names head-wide state, never another session's choice. */
    public fun view(sessionId: String?): AccountPoolView {
        val at = now()
        val session = synchronized(sessions) { sessionId?.let(sessions::get) }
        val current = membership.get()
        val accounts = current.accounts
        accounts.forEach { it.refreshCredentialEvidence() }
        return AccountPoolView(
            selectedLabel = session?.label?.takeIf(current.byLabel::containsKey),
            accounts = accounts.map { account -> accountView(account, session?.label, at) },
            lastSwitch = if (sessionId == null) headLastSwitch.get() else session?.lastSwitch,
            blockedUntilEpochSecondsByLabel = accounts.mapNotNull { account ->
                AccountAvailability.blockedUntil(account, at)?.let { account.label to it }
            }.toMap(),
        )
    }

    /** The next reset when a provider quota hold blocks all credential-selectable accounts.
     *  Authentication-only holds have no provider reset. Missing credentials and auth exclusions
     *  cannot make the pool appear free. Reuse selection's
     *  reset calculation, rather than re-deriving a second account horizon. A property,
     *  not a function: the class sits at detekt's 15-function ceiling (see [AccountAvailability]). */
    public val providerResetForMs: Long
        get() {
            val accounts = membership.get().accounts
            if (accounts.none { it.cooldown.providerUnavailableForMs() > 0L }) return 0L
            accounts.forEach { it.refreshCredentialEvidence() }
            val at = now()
            val free = accounts.any {
                it.credentialStatus(at).selectable && !it.quotaHeld && it.cooldown.providerUnavailableForMs() <= 0L
            }
            if (free) return 0L
            val reset = AccountAvailability.earliestReset(accounts, at) ?: return 0L
            return (reset * MS_PER_SECOND - at).coerceAtLeast(0L)
        }

    /** Independent product sends observe the captured login's actual cooldown, never a sibling's. */
    public val responseCooldowns: Map<String, splice.upstream.retry.RateLimitCooldown>
        get() = membership.get().byLabel.mapValues { it.value.cooldown }

    /** Clears only runtime stickiness/cooldowns; persisted quota and credential files stay untouched. */
    public fun reset() {
        synchronized(sessions) { sessions.clear() }
        synchronized(statelessLock) { statelessPrevious = null }
        headLastSwitch.set(null)
        pinnedLabel.set(null)
        membership.get().accounts.forEach {
            it.cooldown.clear()
            it.cooldown.clearUnavailable()
            it.resetCredentialAvailability()
        }
    }

    /** Pins [label] as the account [select] tries FIRST, ahead of the primary preference, until
     *  [unpin] or the next [reset]. False (nothing pinned) when [label] names no account here. */
    public fun pin(label: String): Boolean {
        val account = membership.get().byLabel[label] ?: return false
        pinnedLabel.set(account.label)
        return true
    }

    public fun unpin() {
        pinnedLabel.set(null)
    }

    /** The currently pinned label, or null when nothing is pinned. Safe for an operator surface —
     *  no credential material, just the label [select] already exposes elsewhere. */
    public fun pinned(): String? = pinnedLabel.get()?.takeIf(membership.get().byLabel::containsKey)

    /** The label [select] would choose next for [sessionId] (null = head-wide), without acquiring
     *  a credential lease — a read-only probe for an operator surface (GET /api/accounts "the next
     *  target by the real selector order"). Walks the exact same [candidates] order [choose] does,
     *  testing only [available]: [acquireIfAvailable] takes a probe lease, which this must not. */
    public fun nextTargetLabel(sessionId: String? = null): String? {
        val at = now()
        val previousLabel = synchronized(sessions) { sessionId?.let { sessions[it]?.label } }
        val current = membership.get()
        current.accounts.forEach { it.refreshCredentialEvidence() }
        val order = candidates(previousLabel, current)
        val free = AccountAvailability.preferredFree(order, at).firstOrNull()
        return (free ?: AccountAvailability.nearestHeld(order, at).firstOrNull())?.label
    }

    /** One order for selection and its preview. Default sessions stay on their free login, then spend
     *  quota that resets soonest. A persisted operator order retains explicit priority, including primary. */
    private fun candidates(previousLabel: String?, current: AccountMembership): List<PoolAccount> {
        val byLabel = current.byLabel
        val primary = current.primary
        val pin = pinnedLabel.get()?.let(byLabel::get)
        val previous = previousLabel?.let(byLabel::get)
        val byReset = current.accounts.sortedWith(AccountAvailability.resetOrder(now()))
        val ordered = orderedLabels.get().mapNotNull(byLabel::get)
        val policy = if (ordered.isEmpty()) {
            listOfNotNull(previous) + byReset
        } else {
            ordered + listOfNotNull(primary, previous) + byReset
        }
        return (listOfNotNull(pin) + policy).distinctBy { it.label }
    }

    /** The first free login in [candidates] order. When every selectable login is held on its plan, the one whose
     *  reset is nearest: its turn is answered with that login's own refusal while its horizon is armed, and is the
     *  probe that notices a top-up once it lifts (V4-47), exactly as a head with one login behaves. */
    private fun choose(
        previousLabel: String?,
        at: Long,
        excluded: Set<String>,
        current: AccountMembership,
    ): ChosenAccount? {
        val order = candidates(previousLabel, current).filter { it.label !in excluded }
        val free = AccountAvailability.preferredFree(order, at)
        val eligible = if (excluded.isEmpty()) free.ifEmpty { AccountAvailability.nearestHeld(order, at) } else free
        return eligible.firstNotNullOfOrNull { acquire(it, at) }
    }

    private fun acquire(account: PoolAccount, at: Long): ChosenAccount? =
        account.acquireCredential(at, now)?.let { lease -> ChosenAccount(account, lease) }

    // V4-132 added the pin branch as a fifth case in the SAME `when` (rather than a fourth early
    // `return`) to stay under ReturnCount's limit of 3 — one `return when`, whatever its arm count.
    // Which limit stopped the previous account is [AccountAvailability.limitReason], so this stays under the
    // complexity ceiling as limits are added (the plan hold was the latest).
    private fun switchReason(
        previousLabel: String,
        chosen: PoolAccount,
        at: Long,
        current: AccountMembership,
    ): String {
        val previous = current.byLabel[previousLabel] ?: return AccountSwitchReason.ACCOUNT_UNAVAILABLE_REASON
        return when {
            chosen.label == pinnedLabel.get() -> AccountSwitchReason.PINNED
            chosen.label in orderedLabels.get() && AccountAvailability.available(previous, at) ->
                AccountSwitchReason.ORDERED
            !AccountAvailability.available(previous, at) -> AccountAvailability.limitReason(previous)
            AccountAvailability.fullReading(previous, at) -> AccountSwitchReason.USAGE_READING_FULL
            chosen.primary -> AccountSwitchReason.PRIMARY_RESET
            else -> AccountSwitchReason.RESET_SOONER
        }
    }

    private fun accountView(account: PoolAccount, selected: String?, at: Long): AccountView {
        val snapshot = account.quotaSnapshot
        val credential = account.credentialStatus(at)
        return AccountView(
            label = account.label,
            primary = account.primary,
            selected = account.label == selected,
            plan = snapshot?.plan,
            fiveHourUsedPercent = snapshot?.fiveHour?.usedPercent,
            fiveHourResetEpochSeconds = snapshot?.fiveHour?.resetsAt,
            sevenDayUsedPercent = snapshot?.sevenDay?.usedPercent,
            sevenDayResetEpochSeconds = snapshot?.sevenDay?.resetsAt,
            available = AccountAvailability.available(account, at),
            credentialPresent = credential.credentialPresent,
            authExcludedUntilEpochMillis = credential.excludedUntilEpochMillis,
            authExclusionReason = credential.reason,
            // V4-132 (GET /api/accounts, FEATURES.md §4.5): the window's own reported LENGTH,
            // dropped by every projection before this row even though QuotaWindow has carried it
            // since Quota.kt:14 — a provider that reports a 30-day period (Grok) or a 7-day one
            // must not be rendered as though both were the same "weekly" bar.
            fiveHourWindowSeconds = snapshot?.fiveHour?.windowSeconds,
            sevenDayWindowSeconds = snapshot?.sevenDay?.windowSeconds,
            quotaObservedAtEpochSeconds = snapshot?.observedAtEpochSeconds,
        )
    }

    private data class ChosenAccount(
        val account: PoolAccount,
        val lease: AccountCredentialEligibility.Lease,
    )

    private data class SessionAccount(val label: String, val lastSwitch: AccountSwitch?)
}

/** Pure per-account availability/quota math — every function here takes the [PoolAccount] (and
 *  [at]) it needs and touches no [AccountPool] state, so V4-132 split it out rather than push
 *  [AccountPool] past detekt's 15-function class ceiling (TooManyFunctions) with its new
 *  pin/unpin/pinned/nextTargetLabel surface. Behaviour is byte-identical to the methods it
 *  replaces — a relocation, not a rewrite. */
private object AccountAvailability {
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

    /** Full readings guide spending, never synthesize a refusal that the provider did not make. */
    fun preferredFree(order: List<PoolAccount>, at: Long): List<PoolAccount> {
        val free = order.filter { available(it, at) }
        return free.filterNot { fullReading(it, at) }.ifEmpty { free }
    }

    fun fullReading(account: PoolAccount, at: Long): Boolean = account.quotaSnapshot?.let {
        exhausted(it.fiveHour, at) || exhausted(it.sevenDay, at)
    } == true

    /** Why [account] stopped serving, the plan the provider named spent first. */
    fun limitReason(account: PoolAccount): String {
        val plan = account.cooldown.planHold.live()
        return when {
            plan != null -> AccountSwitchReason.planLimit(plan.windowWords)
            account.cooldown.rateLimitReply != null && account.cooldown.remainingMs() > 0L ->
                AccountSwitchReason.PROVIDER_LIMIT
            account.cooldown.unavailableForMs() > 0L -> AccountSwitchReason.WAIT_BUDGET
            else -> AccountSwitchReason.ACCOUNT_UNAVAILABLE_REASON
        }
    }

    fun resetOrder(at: Long): Comparator<PoolAccount> =
        compareBy<PoolAccount> { account ->
            val quota = account.quotaSnapshot
            rankingWindow(account, quota?.sevenDay, at) == null && rankingWindow(account, quota?.fiveHour, at) == null
        }.thenBy { !available(it, at) }
            .thenBy { account ->
                listOfNotNull(
                    rankingWindow(account, account.quotaSnapshot?.fiveHour, at)?.resetsAt,
                    rankingWindow(account, account.quotaSnapshot?.sevenDay, at)?.resetsAt,
                ).minOrNull() ?: Long.MAX_VALUE
            }
            .thenBy { rankingWindow(it, it.quotaSnapshot?.sevenDay, at)?.resetsAt ?: Long.MAX_VALUE }
            .thenBy { rankingWindow(it, it.quotaSnapshot?.fiveHour, at)?.resetsAt ?: Long.MAX_VALUE }
            .thenByDescending { it.primary }
            .thenBy { it.label }

    private fun rankingWindow(account: PoolAccount, window: QuotaWindow?, at: Long): QuotaWindow? =
        window?.takeIf {
            val reset = it.resetsAt
            available(account, at) && (reset == null || reset > at / MS_PER_SECOND)
        }

    fun exhausted(window: QuotaWindow?, at: Long): Boolean {
        if (window == null || window.usedPercent < FULLY_USED) return false
        val reset = window.resetsAt ?: return false
        return reset * MS_PER_SECOND > at
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
        val extraSecond = if (remaining % MS_PER_SECOND == 0L) 0L else 1L
        val cooldownSeconds = remaining / MS_PER_SECOND + extraSecond
        val epochSeconds = at / MS_PER_SECOND
        val cooldownReset = epochSeconds + cooldownSeconds
        val authReset = credential.excludedUntilEpochMillis?.let { millis ->
            val seconds = millis / MS_PER_SECOND
            val rounded = millis % MS_PER_SECOND > 0L && seconds < Long.MAX_VALUE
            seconds + if (rounded) 1L else 0L
        }
        return listOfNotNull(quotaReset, cooldownReset.takeIf { remaining > 0L }, authReset).maxOrNull()
    }
}

/** Pool-produced switch reasons, including named provider windows and legacy persisted wording. */
public object AccountSwitchReason {
    internal const val PINNED = "operator pinned this account"
    internal const val ORDERED = "operator account order"
    internal const val PRIMARY_RESET = "primary account reset"
    internal const val RESET_SOONER = "quota resets sooner"
    internal const val PROVIDER_LIMIT = "provider rate limit reached"
    internal const val WAIT_BUDGET = "rate limit exceeds turn wait budget"
    internal const val FIVE_HOUR_QUOTA = "5-hour quota exhausted"
    internal const val SEVEN_DAY_QUOTA = "7-day quota exhausted"
    internal const val USAGE_READING_FULL = "quota usage reading full"
    internal const val ACCOUNT_UNAVAILABLE_REASON = "account unavailable"

    private val reasons = setOf(
        PINNED,
        ORDERED,
        PRIMARY_RESET,
        RESET_SOONER,
        PROVIDER_LIMIT,
        WAIT_BUDGET,
        FIVE_HOUR_QUOTA,
        SEVEN_DAY_QUOTA,
        USAGE_READING_FULL,
        ACCOUNT_UNAVAILABLE_REASON,
        planLimit("5-hour"),
        planLimit("7-day"),
        "7d window exhausted",
    )
    private val planWindow = Regex("7-day [A-Za-z0-9][A-Za-z0-9 -]{0,63} plan limit reached")

    internal fun planLimit(windowWords: String): String = "$windowWords plan limit reached"

    /** Foreign prose and terminal controls never become printable switch reasons. */
    public fun isSafe(reason: String): Boolean = reason in reasons || planWindow.matches(reason)
}
