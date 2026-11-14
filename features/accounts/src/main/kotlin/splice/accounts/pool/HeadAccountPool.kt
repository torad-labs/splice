// NEW: v0.4.0 FEATURES.md §11 — secret-free account-pool state shared by control surfaces.
package splice.accounts.pool

import splice.core.auth.AuthDescription
import splice.core.failure.AccountBlock
import splice.core.usage.ModelQuota
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaFull
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindowView

/** Secret-free account-pool state safe for every operator surface, including the statusline. */
public fun interface HeadAccountPoolSource {
    /** False only for a dormant forwarded caller with no stored account choice. */
    public val active: Boolean get() = true

    public fun view(sessionId: String?): HeadAccountPoolView
}

/** Masked auth descriptions for the bearer-guarded /api/auth surface only. */
public fun interface HeadAccountAuthSource {
    public suspend fun descriptions(): Map<String, AuthDescription>
}

/** V4-132: the REAL pin behind POST /api/auth/{head}/switch (FEATURES.md §4.5 "Manual switch").
 *
 *  A SIBLING of [HeadAccountPoolSource], never a second method on it: that one-method fun
 *  interface is implemented by test doubles tree-wide through SAM conversion (a bare lambda), and
 *  widening it would break every one of them for a capability only the real [splice.upstream.credentials.AccountPool]
 *  has. A route discovers this with a checked cast — the same idiom [splice.usage.statusline.StatuslineRoute]
 *  already uses for [HeadPerfSkipSource] (`managed.perf as? HeadPerfSkipSource`). */
public interface HeadAccountPinSource {
    /** False (nothing pinned) when [label] names no account in this head's pool. A [sessionId] pins that
     *  session alone; null pins the whole head. */
    public fun pin(label: String, sessionId: String? = null): Boolean

    /** Hands the choice back to the pool's own policy from the next turn (console review
     *  2026-09-24: the console could pin and never unpin). A no-op when nothing is pinned. */
    public fun unpin(sessionId: String? = null)
}

/** Try again on a login its provider refused, behind POST /api/auth/{head}/ask. A SIBLING of [HeadAccountPoolSource]
 *  for the reason [HeadAccountPinSource] is one, and found by the same checked cast. */
public fun interface HeadAccountAskSource {
    /** Lets go of whatever holds [label] out for its credential, so the next turn that reaches it asks its provider
     *  again. False when [label] names no account in this head's pool. */
    public fun askAgain(label: String): Boolean
}

/** The login a pool would send its next turn to, by the real selector order, and the model that answer is for: the
 *  one most of the command's recent turns asked for, null until a turn has asked for one. [asked] is every model the
 *  command's turns asked for in the last hour, the latest first: [model] is one answer, and what is said about the
 *  command's logins has to hold for each model someone is waiting on. */
public data class HeadNextTarget(
    val label: String? = null,
    val model: String? = null,
    val asked: List<String> = emptyList(),
)

public data class HeadAccountPoolView(
    val selectedLabel: String?,
    val accounts: List<HeadAccountView>,
    val lastSwitch: HeadAccountSwitchView?,
    /** A text projection rejected selection evidence; unlike a head-wide null, it cannot name the primary. */
    val selectionUnknown: Boolean = false,
    /** V4-132: the operator-pinned label, or null when nothing is pinned. */
    val pinnedLabel: String? = null,
    /** V4-132: what [splice.upstream.credentials.AccountPool.select] would choose next, by the real selector order —
     *  GET /api/accounts "the next target by the real selector order" (FEATURES.md §4.5) — and for which model. */
    val next: HeadNextTarget = HeadNextTarget(),
    /** Authoritative per-account hold deadlines from pool selection, not raw quota window resets. */
    val blockedUntilEpochSecondsByLabel: Map<String, Long> = emptyMap(),
) {
    /** The label of [next]. */
    val nextTargetLabel: String? get() = next.label

    /** The model [nextTargetLabel] is answered for: the one most of the command's recent turns asked for. */
    val usualModel: String? get() = next.model

    /** Null when the selection is unknown: a rejected selection never names the primary, on any
     *  surface (status line, usage payload, CLI text, doctor report alike). A head-wide view, which has no
     *  session's choice, names the login the next turn goes to by the real selector order, and the primary
     *  only when the pool names none: status and doctor named the primary while the next turn went to a backup. */
    public fun selectedAccount(): HeadAccountView? =
        if (selectionUnknown) {
            null
        } else {
            selectedLabel?.let { selected -> accounts.find { it.label == selected } }
                ?: nextTargetLabel?.let { next -> accounts.find { it.label == next } }
                ?: accounts.find(HeadAccountView::primary)
        }

    /** The selected account's windows, or null when it has none yet (a fresh sign-in, a failing
     *  probe): callers then fall through to the head's tracked quota and the client's rate_limits
     *  instead of rendering empty bars. */
    public fun selectedQuota(): QuotaView? {
        val account = selectedAccount() ?: return null
        val observed = account.quota.observedAtEpochSeconds
        val windowView = { window: HeadAccountWindow ->
            window.usedPercent?.let { used ->
                QuotaWindowView(used.toInt(), window.resetEpochSeconds, observed, window.windowSeconds)
            }
        }
        val fiveHour = windowView(account.quota.fiveHour)
        val sevenDay = windowView(account.quota.sevenDay)
        if (fiveHour != null || sevenDay != null) return QuotaView(fiveHour, sevenDay, account.plan)
        // A provider that ANSWERED with no usage is an answer: the account has no bars, and says so.
        return account.quota.noUsageAtEpochSeconds?.let { QuotaView(null, null, account.plan, noUsageAt = it) }
    }

    /** What to show for the selected account: its own reading, else [tracked], the HEAD's reading. The head's
     *  tracker follows the primary's file, so it stands in only while the primary (or no pool selection) is the
     *  account in view: drawn under another account's name it would be the primary's bars on the wrong account
     *  (review of adf35c39e, finding 3). An account with no reading yet shows none. */
    public fun quotaOr(tracked: QuotaView?): QuotaView? = selectedQuota() ?: tracked?.takeIf { headQuotaApplies() }

    /** Whether the head's own tracked quota can stand in for the selected account: only the primary's. */
    public fun headQuotaApplies(): Boolean = selectedAccount()?.primary != false
}

public data class HeadAccountView(
    val label: String,
    val primary: Boolean,
    val selected: Boolean,
    val available: Boolean,
    val plan: String?,
    /** V4-132: each window carries its own reported length (see [splice.upstream.credentials.AccountView]). */
    val quota: HeadAccountQuota = HeadAccountQuota(),
    val credential: HeadAccountCredential = HeadAccountCredential(),
)

/** The plan windows the provider named spent on a head's account while they hold, as it named them: [wholeLogin]
 *  refuses every turn, and each of [models] refuses one model's turns alone, the soonest reset first. */
public data class HeadAccountHolds(val wholeLogin: PlanLimit? = null, val models: List<PlanLimit> = emptyList())

/** A head account's credential standing: whether splice can load it, and the timed authentication hold, if any,
 *  that keeps it out of selection, with the reason. */
public data class HeadAccountCredential(
    val present: Boolean = true,
    val excludedUntilEpochMillis: Long? = null,
    val exclusionReason: String? = null,
    /** Set from the provider refusing the login until its owner acts, which no sign-in changes, until the login
     *  serves a turn again: through its hold and after the hold's time has run out. */
    val ownerBlock: AccountBlock? = null,
    /** An expired token its own client renews the next time it runs: it cannot send, and nobody signs in again. */
    val renewable: Boolean = false,
)

/** A head account's quota: its two windows and when they were observed, epoch SECONDS (the reset fields' unit), or
 *  null when its tracker names no observation. */
public data class HeadAccountQuota(
    val fiveHour: HeadAccountWindow = HeadAccountWindow(),
    val sevenDay: HeadAccountWindow = HeadAccountWindow(),
    val observedAtEpochSeconds: Long? = null,
    /** Each model's own weekly window, where the provider reports one (Claude). */
    val sevenDayModels: List<ModelQuota> = emptyList(),
    /** Epoch SECONDS the provider last answered with no usage for this account (QuotaView.noUsageAt). */
    val noUsageAtEpochSeconds: Long? = null,
    /** The plan windows the provider named spent on this account (Nov 3, 2026). */
    val holds: HeadAccountHolds = HeadAccountHolds(),
    /** The window the selector reads full, and its reset: such a login serves after every login with room. */
    val readsFull: QuotaFull? = null,
)

/** One quota window of a head's account as the control surface reads it: how full it is, when it resets (epoch
 *  SECONDS) and its own reported length in seconds. Every field is null when the account names no such window. */
public data class HeadAccountWindow(
    val usedPercent: Double? = null,
    val resetEpochSeconds: Long? = null,
    val windowSeconds: Long? = null,
)

public data class HeadAccountSwitchView(
    val from: String,
    val to: String,
    val reason: String,
    val atEpochMillis: Long,
)
