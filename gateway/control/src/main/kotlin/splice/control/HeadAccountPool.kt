// NEW: v0.4.0 FEATURES.md §11 — secret-free account-pool state shared by control surfaces.
package splice.control

import splice.core.auth.AuthDescription

/** Secret-free account-pool state safe for every operator surface, including the statusline. */
public fun interface HeadAccountPoolSource {
    public fun view(sessionId: String?): HeadAccountPoolView
}

/** Masked auth descriptions for the bearer-guarded /api/auth surface only. */
public fun interface HeadAccountAuthSource {
    public suspend fun descriptions(): Map<String, AuthDescription>
}

public data class HeadAccountPoolView(
    val selectedLabel: String?,
    val accounts: List<HeadAccountView>,
    val lastSwitch: HeadAccountSwitchView?,
    /** A text projection rejected selection evidence; unlike a head-wide null, it cannot name the primary. */
    val selectionUnknown: Boolean = false,
) {
    public fun selectedAccount(): HeadAccountView? =
        selectedLabel?.let { selected -> accounts.find { it.label == selected } }
            ?: accounts.find(HeadAccountView::primary)

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
)

public data class HeadAccountSwitchView(
    val from: String,
    val to: String,
    val reason: String,
    val atEpochMillis: Long,
)
