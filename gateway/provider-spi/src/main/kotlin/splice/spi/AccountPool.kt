// NEW: v0.4.0 FEATURES.md §11 — sticky per-session selection across OAuth accounts.
package splice.spi

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

/** A head-local OAuth account pool. A returned [AccountSelection] is immutable for the whole turn. */
public class AccountPool(
    accounts: List<PoolAccount>,
    private val now: WallClock,
) {
    private val accounts = accounts.toList()
    private val byLabel = accounts.associateBy(PoolAccount::label)
    private val primary = accounts.singleOrNull(PoolAccount::primary)

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

    init {
        require(accounts.isNotEmpty()) { "account pool must not be empty" }
        require(byLabel.size == accounts.size) { "account labels must be unique" }
        require(accounts.count(PoolAccount::primary) == 1) { "account pool must have exactly one primary" }
    }

    /** Chooses once at the turn boundary. Null sessions re-evaluate policy without becoming sticky.
     *  Returns [Selection.Chosen] with the turn's immutable choice, or [Selection.Exhausted] when no
     *  account can start a turn — an ordinary, expected refusal, so it is a value, not a throw. */
    public fun select(sessionId: String?): Selection {
        require(sessionId == null || sessionId.isNotBlank()) { "session id must not be blank" }
        val at = now()
        // Credential evidence is read (and hashed) OUTSIDE the sticky-session monitor: the lock only
        // keeps the LinkedHashMap consistent, and holding it across a filesystem round-trip makes its
        // contention window the disk's latency. Each account caches the read behind a short TTL, so
        // the in-monitor selection below reads the cache, never the credential file.
        accounts.forEach { it.refreshCredentialEvidence() }
        if (sessionId == null) return selectStateless(at)
        return synchronized(sessions) {
            val chosen = selected(sessions[sessionId], at, sticky = true)
            chosen.second?.let { session ->
                sessions[sessionId] = session
                if (sessions.size > MAX_TRACKED_SESSIONS) sessions.remove(sessions.keys.first())
            }
            chosen.first
        }
    }

    private fun selectStateless(at: Long): Selection = synchronized(statelessLock) {
        val chosen = selected(statelessPrevious, at, sticky = false)
        chosen.second?.let { statelessPrevious = it }
        chosen.first
    }

    private fun selected(
        previous: SessionAccount?,
        at: Long,
        sticky: Boolean,
    ): Pair<Selection, SessionAccount?> {
        val chosen = choose(if (sticky) previous?.label else null, at)
            ?: return Selection.Exhausted(AccountAvailability.earliestReset(accounts, at)) to null
        // A new session starts relative to primary even when its credential is missing: choosing
        // a backup is cache-cold on that first turn and updates the head-wide last-switch notice.
        val prior = previous ?: primary?.let { SessionAccount(it.label, null) }
        val moved = prior?.takeIf { it.label != chosen.account.label }?.let {
            AccountSwitch(it.label, chosen.account.label, switchReason(it.label, chosen.account, at), at)
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
        return AccountPoolView(
            selectedLabel = session?.label,
            accounts = accounts.map { account -> accountView(account, session?.label, at) },
            lastSwitch = if (sessionId == null) headLastSwitch.get() else session?.lastSwitch,
        )
    }

    /** Clears only runtime stickiness/cooldowns; persisted quota and credential files stay untouched. */
    public fun reset() {
        synchronized(sessions) { sessions.clear() }
        synchronized(statelessLock) { statelessPrevious = null }
        headLastSwitch.set(null)
        pinnedLabel.set(null)
        accounts.forEach {
            it.cooldown.clear()
            it.cooldown.clearUnavailable()
            it.resetCredentialAvailability()
        }
    }

    /** Pins [label] as the account [select] tries FIRST, ahead of the primary preference, until
     *  [unpin] or the next [reset]. False (nothing pinned) when [label] names no account here. */
    public fun pin(label: String): Boolean {
        val account = byLabel[label] ?: return false
        pinnedLabel.set(account.label)
        return true
    }

    public fun unpin() {
        pinnedLabel.set(null)
    }

    /** The currently pinned label, or null when nothing is pinned. Safe for an operator surface —
     *  no credential material, just the label [select] already exposes elsewhere. */
    public fun pinned(): String? = pinnedLabel.get()

    /** The label [select] would choose next for [sessionId] (null = head-wide), without acquiring
     *  a credential lease — a read-only probe for an operator surface (GET /api/accounts "the next
     *  target by the real selector order"). Walks the exact same [candidates] order [choose] does,
     *  testing only [available]: [acquireIfAvailable] takes a probe lease, which this must not. */
    public fun nextTargetLabel(sessionId: String? = null): String? {
        val at = now()
        val previousLabel = if (sessionId == null) {
            synchronized(statelessLock) { statelessPrevious?.label }
        } else {
            synchronized(sessions) { sessions[sessionId]?.label }
        }
        return candidates(previousLabel).firstOrNull { AccountAvailability.available(it, at) }?.label
    }

    /** The one selection order [choose] and [nextTargetLabel] both walk: the pin (if any), then
     *  primary, then the caller's previous account, then every account by lowest seven-day used —
     *  [distinctBy] below collapses whichever of those coincide (previous === primary is the
     *  common case) so no account is probed twice in one call. Behaviour-identical to the pre-pin
     *  order when nothing is pinned: that used to special-case "previous === primary" to avoid a
     *  duplicate probe: same effect, one list. */
    private fun candidates(previousLabel: String?): List<PoolAccount> {
        val pin = pinnedLabel.get()?.let(byLabel::get)
        val previous = previousLabel?.let(byLabel::get)
        val bySevenDay = accounts.sortedWith(
            compareBy<PoolAccount>(AccountAvailability::sevenDayUsed).thenBy { it.label },
        )
        return (listOfNotNull(pin, primary, previous) + bySevenDay).distinctBy { it.label }
    }

    private fun choose(previousLabel: String?, at: Long): ChosenAccount? =
        candidates(previousLabel).firstNotNullOfOrNull { acquireIfAvailable(it, at) }

    private fun acquireIfAvailable(account: PoolAccount, at: Long): ChosenAccount? {
        if (!AccountAvailability.available(account, at)) return null
        val lease = account.acquireCredential(at, now) ?: return null
        return ChosenAccount(account, lease)
    }

    // V4-132 added the pin branch as a fifth case in the SAME `when` (rather than a fourth early
    // `return`) to stay under ReturnCount's limit of 3 — one `return when`, whatever its arm count.
    private fun switchReason(previousLabel: String, chosen: PoolAccount, at: Long): String {
        val previous = byLabel.getValue(previousLabel)
        val quota = previous.quota.snapshot()
        return when {
            chosen.label == pinnedLabel.get() -> "operator pinned this account"
            chosen.primary -> "primary account reset"
            previous.cooldown.unavailableForMs() > 0L -> "rate limit exceeds turn wait budget"
            AccountAvailability.exhausted(quota?.fiveHour, at) -> "5-hour quota exhausted"
            AccountAvailability.exhausted(quota?.sevenDay, at) -> "7-day quota exhausted"
            else -> "account unavailable"
        }
    }

    private fun accountView(account: PoolAccount, selected: String?, at: Long): AccountView {
        val snapshot = account.quota.snapshot()
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
    fun available(account: PoolAccount, at: Long): Boolean {
        val runtimeUnavailable = account.cooldown.unavailableForMs() > 0L
        if (!account.credentialStatus(at).selectable || runtimeUnavailable) return false
        val snapshot = account.quota.snapshot() ?: return true
        return !exhausted(snapshot.fiveHour, at) && !exhausted(snapshot.sevenDay, at)
    }

    fun sevenDayUsed(account: PoolAccount): Double = account.quota.snapshot()?.sevenDay?.usedPercent ?: 0.0

    fun exhausted(window: QuotaWindow?, at: Long): Boolean {
        if (window == null || window.usedPercent < FULLY_USED) return false
        val reset = window.resetsAt ?: return false
        return reset * MS_PER_SECOND > at
    }

    fun earliestReset(accounts: List<PoolAccount>, at: Long): Long? =
        accounts.mapNotNull { blockedUntil(it, at) }.minOrNull()

    private fun blockedUntil(account: PoolAccount, at: Long): Long? {
        val snapshot = account.quota.snapshot()
        val blocked = listOfNotNull(snapshot?.fiveHour, snapshot?.sevenDay).filter { exhausted(it, at) }
        val quotaReset = blocked.mapNotNull(QuotaWindow::resetsAt).maxOrNull()
        val remaining = account.cooldown.providerUnavailableForMs()
        val extraSecond = if (remaining % MS_PER_SECOND == 0L) 0L else 1L
        val cooldownSeconds = remaining / MS_PER_SECOND + extraSecond
        val epochSeconds = at / MS_PER_SECOND
        val cooldownReset = epochSeconds + cooldownSeconds
        val authReset = account.credentialStatus(at).excludedUntilEpochMillis?.let { millis ->
            val seconds = millis / MS_PER_SECOND
            val rounded = millis % MS_PER_SECOND > 0L && seconds < Long.MAX_VALUE
            seconds + if (rounded) 1L else 0L
        }
        return listOfNotNull(quotaReset, cooldownReset.takeIf { remaining > 0L }, authReset).maxOrNull()
    }
}
