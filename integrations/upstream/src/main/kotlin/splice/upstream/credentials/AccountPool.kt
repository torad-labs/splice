// NEW: v0.4.0 FEATURES.md §11 — sticky per-session selection across OAuth accounts.
package splice.upstream.credentials

import splice.core.auth.ClientAuthProvider
import splice.core.util.LruSizing
import splice.core.util.WallClock
import java.util.concurrent.atomic.AtomicReference

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

    /** Native login writes outside splice. Reconcile its generation before reading selector state. */
    @Volatile
    public var membershipRefresh: AccountMembershipRefresh? = null

    private val liveMembership: AccountMembership
        get() {
            membershipRefresh?.refresh()
            return membership.get()
        }

    /** Publishes membership only. Existing session choices, order, cooldown objects and leased turns survive. */
    public var members: List<PoolAccount>
        get() = membership.get().accounts
        set(accounts) {
            membership.set(AccountMembership(accounts))
        }

    /** A forwarded caller alone is the legacy path, not a choice between stored logins. */
    public val active: Boolean
        get() = liveMembership.let { it.accounts.size > 1 || it.primary.auth !is ClientAuthProvider }

    // Access order keeps active sessions sticky without retaining every session the daemon ever saw.
    // Reads reorder the map too, so selection, views and reset share its monitor.
    private val sessions = LinkedHashMap<String, SessionAccount>(
        LruSizing.INITIAL_CAPACITY,
        LruSizing.LOAD_FACTOR,
        true,
    )

    /** The lock selection, views and reset share; tests assert nothing slow runs while it is held. */
    internal val sessionMonitor: Any get() = sessions

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
    private val pins = PinBook()
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
        val current = liveMembership
        current.accounts.forEach { it.refreshCredentialEvidence() }
        return candidates(null, current, pins.forSession(null)).map(PoolAccount::label)
    }

    /** Chooses before acceptance. Null sessions re-evaluate policy without becoming sticky.
     *  Nonempty [excluded] skips refused logins and permits only a free login, never a held fallback.
     *  Returns [Selection.Chosen] with an immutable choice, or [Selection.Exhausted] as a refusal value. */
    public fun select(sessionId: String?, excluded: Set<String> = emptySet()): Selection =
        select(sessionId, excluded, null)

    /** A changed caller login takes precedence over automatic stickiness, but never an operator pin. */
    public fun select(sessionId: String?, excluded: Set<String>, callerCredentialKey: String?): Selection {
        require(sessionId == null || sessionId.isNotBlank()) { "session id must not be blank" }
        val at = now()
        // Credential evidence is read (and hashed) OUTSIDE the sticky-session monitor: the lock only
        // keeps the LinkedHashMap consistent, and holding it across a filesystem round-trip makes its
        // contention window the disk's latency. Each account caches the read behind a short TTL, so
        // the in-monitor selection below reads the cache, never the credential file.
        val current = liveMembership
        current.accounts.forEach { it.refreshCredentialEvidence() }
        val caller = CallerLogin(callerCredentialKey, sessionId, current.accounts)
        if (sessionId == null) {
            return synchronized(statelessLock) {
                val chosen = selected(statelessPrevious, at, excluded, current, caller)
                chosen.second?.let { statelessPrevious = it }
                chosen.first
            }
        }
        return synchronized(sessions) {
            val chosen = selected(sessions[sessionId], at, excluded, current, caller)
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
        excluded: Set<String>,
        current: AccountMembership,
        caller: CallerLogin,
    ): Pair<Selection, SessionAccount?> {
        val chosen = choose(previous, at, excluded, current, caller)
            ?: return Selection.Exhausted(AccountAvailability.earliestReset(current.accounts, at)) to null
        // A new session starts relative to primary even when its credential is missing: choosing
        // a backup is cache-cold on that first turn and updates the head-wide last-switch notice.
        val prior = previous ?: SessionAccount(current.primary.label, null)
        val moved = prior.takeIf { it.label != chosen.account.label }?.let {
            val reason = switchReason(it.label, chosen.account, at, current, pins.forSession(caller.session))
            AccountSwitch(it.label, chosen.account.label, reason, at)
        }
        moved?.let(headLastSwitch::set)
        val selection = Selection.Chosen(AccountSelection(chosen.account, moved, chosen.lease))
        val session = SessionAccount(
            chosen.account.label,
            moved ?: previous?.lastSwitch,
            caller.remembered(previous),
        )
        return selection to session
    }

    /** Safe state for operator surfaces; null names head-wide state, never another session's choice. */
    public fun view(sessionId: String?): AccountPoolView {
        val at = now()
        val session = synchronized(sessions) { sessionId?.let(sessions::get) }
        val current = liveMembership
        val accounts = current.accounts
        accounts.forEach { it.refreshCredentialEvidence() }
        return AccountPoolView(
            selectedLabel = session?.label?.takeIf(current.byLabel::containsKey),
            accounts = accounts.map { account -> accountView(account, session?.label, at) },
            lastSwitch = if (sessionId == null) headLastSwitch.get() else session?.lastSwitch,
            blockedUntilEpochSecondsByLabel = accounts.mapNotNull { account ->
                AccountAvailability.blockedUntil(account, at)?.let { account.label to it }
            }.toMap(),
            // Where the command moves when the account it would use next runs out: the same pick over the order
            // [nextTargetLabel] walks, with that account left out. Here, not a function: the class is at the ceiling.
            followingLabel = AccountAvailability.upNext(
                candidates(session?.label, current, pins.forSession(sessionId)),
                at,
            ).getOrNull(1)?.label,
        )
    }

    /** The next reset when a provider quota hold blocks all credential-selectable accounts.
     *  Authentication-only holds have no provider reset. Missing credentials and auth exclusions
     *  cannot make the pool appear free. Reuse selection's
     *  reset calculation, rather than re-deriving a second account horizon. A property,
     *  not a function: the class sits at detekt's 15-function ceiling (see [AccountAvailability]). */
    public val providerResetForMs: Long
        get() {
            val accounts = liveMembership.accounts
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
        get() = liveMembership.byLabel.mapValues { it.value.cooldown }

    /** Clears only runtime stickiness/cooldowns; persisted quota and credential files stay untouched. */
    public fun reset() {
        synchronized(sessions) { sessions.clear() }
        synchronized(statelessLock) { statelessPrevious = null }
        headLastSwitch.set(null)
        pins.clear()
        membership.get().accounts.forEach {
            it.cooldown.clear()
            it.cooldown.clearUnavailable()
            it.resetCredentialAvailability()
        }
    }

    /** Pins [label] as the account [select] tries FIRST, ahead of the primary preference, until
     *  [unpin] or the next [reset]. False (nothing pinned) when [label] names no account here. */
    public fun pin(label: String, sessionId: String? = null): Boolean {
        val account = liveMembership.byLabel[label] ?: return false
        pins.pin(account.label, sessionId)
        return true
    }

    public fun unpin(sessionId: String? = null) {
        pins.unpin(sessionId)
    }

    /** The currently pinned label, or null when nothing is pinned. Safe for an operator surface —
     *  no credential material, just the label [select] already exposes elsewhere. */
    public fun pinned(sessionId: String? = null): String? =
        pins.forSession(sessionId)?.takeIf(membership.get().byLabel::containsKey)

    /** The label [select] would choose next for [sessionId] (null = head-wide), without acquiring
     *  a credential lease — a read-only probe for an operator surface (GET /api/accounts "the next
     *  target by the real selector order"). Walks the exact same [candidates] order [choose] does,
     *  testing only [available]: [acquireIfAvailable] takes a probe lease, which this must not. */
    public fun nextTargetLabel(sessionId: String? = null): String? {
        val previousLabel = synchronized(sessions) { sessionId?.let { sessions[it]?.label } }
        val current = liveMembership
        current.accounts.forEach { it.refreshCredentialEvidence() }
        val order = candidates(previousLabel, current, pins.forSession(sessionId))
        return AccountAvailability.upNext(order, now()).firstOrNull()?.label
    }

    /** One order for selection and its preview. Default sessions stay on their free login, then spend
     *  quota that resets soonest. A persisted operator order retains explicit priority, including primary. */
    private fun candidates(previousLabel: String?, current: AccountMembership, pinLabel: String?): List<PoolAccount> {
        val byLabel = current.byLabel
        val primary = current.primary
        val pin = pinLabel?.let(byLabel::get)
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
        previous: SessionAccount?,
        at: Long,
        excluded: Set<String>,
        current: AccountMembership,
        caller: CallerLogin,
    ): ChosenAccount? {
        val pinLabel = pins.forSession(caller.session)
        val preference = listOfNotNull(
            pinLabel?.let(current.byLabel::get),
            caller.preferred(previous)?.let(current.byLabel::get),
        )
        val order = (preference + candidates(caller.continuesFrom(previous), current, pinLabel))
            .distinctBy(PoolAccount::label)
            .filter { it.label !in excluded }
        val free = AccountAvailability.preferredFree(order, at)
        val eligible = if (excluded.isEmpty()) free.ifEmpty { AccountAvailability.nearestHeld(order, at) } else free
        return eligible.firstNotNullOfOrNull { account ->
            account.acquireCredential(at, now)?.let { lease -> ChosenAccount(account, lease) }
        }
    }

    // V4-132 added the pin branch as a fifth case in the SAME `when` (rather than a fourth early
    // `return`) to stay under ReturnCount's limit of 3 — one `return when`, whatever its arm count.
    // Which limit stopped the previous account is [AccountAvailability.limitReason], so this stays under the
    // complexity ceiling as limits are added (the plan hold was the latest).
    private fun switchReason(
        previousLabel: String,
        chosen: PoolAccount,
        at: Long,
        current: AccountMembership,
        pinLabel: String?,
    ): String {
        val previous = current.byLabel[previousLabel] ?: return AccountSwitchReason.ACCOUNT_UNAVAILABLE_REASON
        return when {
            chosen.label == pinLabel -> AccountSwitchReason.PINNED
            chosen.label in orderedLabels.get() && AccountAvailability.available(previous, at) ->
                AccountSwitchReason.ORDERED
            !AccountAvailability.available(previous, at) -> AccountAvailability.limitReason(previous, at)
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
            available = AccountAvailability.available(account, at),
            quota = AccountQuotaReading(
                fiveHour = AccountWindowReading(
                    usedPercent = snapshot?.fiveHour?.usedPercent,
                    resetEpochSeconds = snapshot?.fiveHour?.resetsAt,
                    // V4-132 (GET /api/accounts, FEATURES.md §4.5): the window's own reported LENGTH,
                    // dropped by every projection before this row even though QuotaWindow has carried it
                    // since Quota.kt:14 — a provider that reports a 30-day period (Grok) or a 7-day one
                    // must not be rendered as though both were the same "weekly" bar.
                    windowSeconds = snapshot?.fiveHour?.windowSeconds,
                ),
                sevenDay = AccountWindowReading(
                    usedPercent = snapshot?.sevenDay?.usedPercent,
                    resetEpochSeconds = snapshot?.sevenDay?.resetsAt,
                    windowSeconds = snapshot?.sevenDay?.windowSeconds,
                ),
                observedAtEpochSeconds = snapshot?.observedAtEpochSeconds,
                sevenDayModels = snapshot?.models.orEmpty(),
                noUsageAtEpochSeconds = snapshot?.takeIf { it.answeredEmpty }
                    ?.let { java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(it.updatedAt) },
            ),
            credential = AccountCredentialReading(
                present = credential.credentialPresent,
                excludedUntilEpochMillis = credential.excludedUntilEpochMillis,
                exclusionReason = credential.reason,
            ),
        )
    }

    private data class ChosenAccount(
        val account: PoolAccount,
        val lease: AccountCredentialEligibility.Lease,
    )

    /** The login the caller presented, and what it decides for one selection: which account it asks for, whether the
     *  session carries its history into the choice, and which login the session remembers afterwards. A call without
     *  a session has no history to carry and nothing to remember it by. */
    private class CallerLogin(private val key: String?, val session: String?, accounts: List<PoolAccount>) {
        private val label: String? = key?.let { presented ->
            accounts.singleOrNull { it.auth.observedCredentialKey() == presented }?.label
        }

        /** The account this login names, unless the session already acted on this same login: a changed login takes
         *  precedence over automatic stickiness, an unchanged one has had its turn. */
        fun preferred(previous: SessionAccount?): String? =
            label.takeIf { session == null || key != previous?.callerKey }

        /** The label the choice starts from: the session's last account, never a stateless call's. */
        fun continuesFrom(previous: SessionAccount?): String? = previous?.label.takeIf { session != null }

        /** The login the session records: this one when presented, else the one it already had. */
        fun remembered(previous: SessionAccount?): String? = key ?: previous?.callerKey
    }

    private data class SessionAccount(val label: String, val lastSwitch: AccountSwitch?, val callerKey: String? = null)
}
