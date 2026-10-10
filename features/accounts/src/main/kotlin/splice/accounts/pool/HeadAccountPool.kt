// NEW: v0.4.0 FEATURES.md §11 — secret-free account-pool state shared by control surfaces.
package splice.accounts.pool

import splice.core.auth.AuthDescription
import splice.core.usage.ModelQuota
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
    /** False (nothing pinned) when [label] names no account in this head's pool. */
    public fun pin(label: String): Boolean

    /** Hands the choice back to the pool's own policy from the next turn (console review
     *  2026-09-24: the console could pin and never unpin). A no-op when nothing is pinned. */
    public fun unpin()
}

public data class HeadAccountPoolView(
    val selectedLabel: String?,
    val accounts: List<HeadAccountView>,
    val lastSwitch: HeadAccountSwitchView?,
    /** A text projection rejected selection evidence; unlike a head-wide null, it cannot name the primary. */
    val selectionUnknown: Boolean = false,
    /** V4-132: the operator-pinned label, or null when nothing is pinned. */
    val pinnedLabel: String? = null,
    /** V4-132: the label [splice.upstream.credentials.AccountPool.select] would choose next, by the real selector
     *  order — GET /api/accounts "the next target by the real selector order" (FEATURES.md §4.5). */
    val nextTargetLabel: String? = null,
    /** Authoritative per-account hold deadlines from pool selection, not raw quota window resets. */
    val blockedUntilEpochSecondsByLabel: Map<String, Long> = emptyMap(),
) {
    /** Null when the selection is unknown: a rejected selection never names the primary, on any
     *  surface (status line, usage payload, CLI text, doctor report alike). */
    public fun selectedAccount(): HeadAccountView? =
        if (selectionUnknown) {
            null
        } else {
            selectedLabel?.let { selected -> accounts.find { it.label == selected } }
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
        return if (fiveHour == null && sevenDay == null) null else QuotaView(fiveHour, sevenDay, account.plan)
    }
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

/** A head account's credential standing: whether splice can load it, and the timed authentication hold, if any,
 *  that keeps it out of selection, with the reason. */
public data class HeadAccountCredential(
    val present: Boolean = true,
    val excludedUntilEpochMillis: Long? = null,
    val exclusionReason: String? = null,
)

/** A head account's quota: its two windows and when they were observed, epoch SECONDS (the reset fields' unit), or
 *  null when its tracker names no observation. */
public data class HeadAccountQuota(
    val fiveHour: HeadAccountWindow = HeadAccountWindow(),
    val sevenDay: HeadAccountWindow = HeadAccountWindow(),
    val observedAtEpochSeconds: Long? = null,
    /** Each model's own weekly window, where the provider reports one (Claude). */
    val sevenDayModels: List<ModelQuota> = emptyList(),
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
