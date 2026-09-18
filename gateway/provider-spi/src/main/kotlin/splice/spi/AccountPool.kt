// NEW: v0.4.0 FEATURES.md §11 — sticky per-session selection across OAuth accounts.
package splice.spi

import splice.core.usage.QuotaWindow
import java.util.concurrent.atomic.AtomicReference

private const val FULLY_USED = 100.0
private const val MS_PER_SECOND = 1_000L
private const val MAX_TRACKED_SESSIONS = 4_096
private const val SESSION_MAP_CAPACITY = 16
private const val SESSION_MAP_LOAD = 0.75f

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
    private val now: AccountNow,
) {
    private val accounts = accounts.toList()
    private val byLabel = accounts.associateBy(PoolAccount::label)
    private val primary = accounts.singleOrNull(PoolAccount::primary)

    // Access order keeps active sessions sticky without retaining every session the daemon ever saw.
    // Reads reorder the map too, so selection, views and reset share its monitor.
    private val sessions = LinkedHashMap<String, SessionAccount>(SESSION_MAP_CAPACITY, SESSION_MAP_LOAD, true)
    private val headLastSwitch = AtomicReference<AccountSwitch?>(null)
    private val statelessLock = Any()
    private var statelessPrevious: SessionAccount? = null

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
            ?: return Selection.Exhausted(earliestReset(at)) to null
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
        accounts.forEach {
            it.cooldown.clear()
            it.cooldown.clearUnavailable()
            it.resetCredentialAvailability()
        }
    }

    private fun choose(previousLabel: String?, at: Long): ChosenAccount? {
        val previous = previousLabel?.let(byLabel::get)
        val primary = checkNotNull(primary)
        val prefersPrimary = previous == null || previous !== primary
        val primaryChoice = primary.takeIf { prefersPrimary }?.let { acquireIfAvailable(it, at) }
        if (primaryChoice != null) return primaryChoice
        val previousChoice = previous?.let { acquireIfAvailable(it, at) }
        if (previousChoice != null) return previousChoice
        val candidates = accounts.sortedWith(compareBy<PoolAccount>(::sevenDayUsed).thenBy { it.label })
        return candidates.firstNotNullOfOrNull { acquireIfAvailable(it, at) }
    }

    private fun acquireIfAvailable(account: PoolAccount, at: Long): ChosenAccount? {
        if (!available(account, at)) return null
        val lease = account.acquireCredential(at, now) ?: return null
        return ChosenAccount(account, lease)
    }

    private fun switchReason(previousLabel: String, chosen: PoolAccount, at: Long): String {
        if (chosen.primary) return "primary account reset"
        val previous = byLabel.getValue(previousLabel)
        if (previous.cooldown.unavailableForMs() > 0L) return "rate limit exceeds turn wait budget"
        val quota = previous.quota.snapshot()
        return when {
            exhausted(quota?.fiveHour, at) -> "5-hour quota exhausted"
            exhausted(quota?.sevenDay, at) -> "7-day quota exhausted"
            else -> "account unavailable"
        }
    }

    private fun earliestReset(at: Long): Long? = accounts.mapNotNull { blockedUntil(it, at) }.minOrNull()

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

    private fun available(account: PoolAccount, at: Long): Boolean {
        val runtimeUnavailable = account.cooldown.unavailableForMs() > 0L
        if (!account.credentialStatus(at).selectable || runtimeUnavailable) return false
        val snapshot = account.quota.snapshot() ?: return true
        return !exhausted(snapshot.fiveHour, at) && !exhausted(snapshot.sevenDay, at)
    }

    private fun sevenDayUsed(account: PoolAccount): Double =
        account.quota.snapshot()?.sevenDay?.usedPercent ?: 0.0

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
            available = available(account, at),
            credentialPresent = credential.credentialPresent,
            authExcludedUntilEpochMillis = credential.excludedUntilEpochMillis,
            authExclusionReason = credential.reason,
        )
    }

    private fun exhausted(window: QuotaWindow?, at: Long): Boolean {
        if (window == null || window.usedPercent < FULLY_USED) return false
        val reset = window.resetsAt ?: return false
        return reset * MS_PER_SECOND > at
    }

    private data class ChosenAccount(
        val account: PoolAccount,
        val lease: AccountCredentialEligibility.Lease,
    )

    private data class SessionAccount(val label: String, val lastSwitch: AccountSwitch?)
}
