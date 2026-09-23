// NEW: v0.4.0 FEATURES.md §11 — secret-free account-pool state shared by control surfaces.
package splice.accounts.pool

import splice.core.auth.AuthDescription
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindowView

/** Secret-free account-pool state safe for every operator surface, including the statusline. */
public fun interface HeadAccountPoolSource {
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
 *  has. A route discovers this with a checked cast — the same idiom [splice.control.api.usage.StatuslineRoute]
 *  already uses for [HeadPerfSkipSource] (`managed.perf as? HeadPerfSkipSource`). */
public fun interface HeadAccountPinSource {
    /** False (nothing pinned) when [label] names no account in this head's pool. */
    public fun pin(label: String): Boolean
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
        val fiveHour = account.fiveHourUsedPercent?.let { used ->
            QuotaWindowView(used.toInt(), account.fiveHourResetEpochSeconds)
        }
        val sevenDay = account.sevenDayUsedPercent?.let { used ->
            QuotaWindowView(used.toInt(), account.sevenDayResetEpochSeconds)
        }
        return if (fiveHour == null && sevenDay == null) null else QuotaView(fiveHour, sevenDay, account.plan)
    }
}

public data class HeadAccountView(
    val label: String,
    val primary: Boolean,
    val selected: Boolean,
    val available: Boolean,
    val plan: String?,
    val fiveHourUsedPercent: Double?,
    val fiveHourResetEpochSeconds: Long?,
    val sevenDayUsedPercent: Double?,
    val sevenDayResetEpochSeconds: Long?,
    val credentialPresent: Boolean = true,
    val authExcludedUntilEpochMillis: Long? = null,
    val authExclusionReason: String? = null,
    /** V4-132: the window's own reported length in seconds (see [splice.upstream.credentials.AccountView]). */
    val fiveHourWindowSeconds: Long? = null,
    val sevenDayWindowSeconds: Long? = null,
)

public data class HeadAccountSwitchView(
    val from: String,
    val to: String,
    val reason: String,
    val atEpochMillis: Long,
)
