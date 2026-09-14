// NEW: v0.4.0 FEATURES.md §11 — sticky per-session selection across OAuth accounts.
package splice.spi

import splice.core.usage.QuotaWindow
import java.util.concurrent.atomic.AtomicReference

private const val FULLY_USED = 100.0
private const val MS_PER_SECOND = 1_000L
private const val MAX_TRACKED_SESSIONS = 4_096
private const val SESSION_MAP_CAPACITY = 16
private const val SESSION_MAP_LOAD = 0.75f

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

    /** Chooses once at the turn boundary. Null sessions re-evaluate policy without becoming sticky. */
    public fun select(sessionId: String?): AccountSelection {
        require(sessionId == null || sessionId.isNotBlank()) { "session id must not be blank" }
        val at = now()
        if (sessionId == null) return selectStateless(at)
        return synchronized(sessions) {
            val chosen = selected(sessions[sessionId], at, sticky = true)
            sessions[sessionId] = chosen.second
            if (sessions.size > MAX_TRACKED_SESSIONS) sessions.remove(sessions.keys.first())
            chosen.first
        }
    }

    private fun selectStateless(at: Long): AccountSelection = synchronized(statelessLock) {
        val chosen = selected(statelessPrevious, at, sticky = false)
        statelessPrevious = chosen.second
        chosen.first
    }

    private fun selected(
        previous: SessionAccount?,
        at: Long,
        sticky: Boolean,
    ): Pair<AccountSelection, SessionAccount> {
        val chosen = choose(if (sticky) previous?.label else null, at)
        // A new session starts relative to primary even when its credential is missing: choosing
        // a backup is cache-cold on that first turn and updates the head-wide last-switch notice.
        val prior = previous ?: primary?.let { SessionAccount(it.label, null) }
        val moved = prior?.takeIf { it.label != chosen.label }?.let {
            AccountSwitch(it.label, chosen.label, switchReason(it.label, chosen, at), at)
        }
        moved?.let(headLastSwitch::set)
        val selection = AccountSelection(chosen, moved)
        val session = SessionAccount(chosen.label, moved ?: previous?.lastSwitch)
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
        }
    }

    private fun choose(previousLabel: String?, at: Long): PoolAccount {
        val previous = previousLabel?.let(byLabel::get)
        val primary = checkNotNull(primary)
        val prefersPrimary = previous == null || previous !== primary
        if (prefersPrimary && available(primary, at)) return primary
        if (previous?.let { available(it, at) } == true) return previous
        val candidates = accounts.filter { available(it, at) }
        if (candidates.isEmpty()) throw AllAccountsExhausted(earliestReset(at))
        return candidates.minWith(compareBy<PoolAccount>(::sevenDayUsed).thenBy { it.label })
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
        return listOfNotNull(quotaReset, cooldownReset.takeIf { remaining > 0L }).maxOrNull()
    }

    private fun available(account: PoolAccount, at: Long): Boolean {
        val runtimeUnavailable = account.cooldown.unavailableForMs() > 0L
        if (!account.credentialPresent || runtimeUnavailable) return false
        val snapshot = account.quota.snapshot() ?: return true
        return !exhausted(snapshot.fiveHour, at) && !exhausted(snapshot.sevenDay, at)
    }

    private fun sevenDayUsed(account: PoolAccount): Double =
        account.quota.snapshot()?.sevenDay?.usedPercent ?: 0.0

    private fun accountView(account: PoolAccount, selected: String?, at: Long): AccountView {
        val snapshot = account.quota.snapshot()
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
            credentialPresent = account.credentialPresent,
        )
    }

    private fun exhausted(window: QuotaWindow?, at: Long): Boolean {
        if (window == null || window.usedPercent < FULLY_USED) return false
        val reset = window.resetsAt ?: return false
        return reset * MS_PER_SECOND > at
    }

    private data class SessionAccount(val label: String, val lastSwitch: AccountSwitch?)
}
