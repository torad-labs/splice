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

/** V4-132: the REAL pin behind POST /api/auth/{head}/switch (FEATURES.md §4.5 "Manual switch").
 *
 *  A SIBLING of [HeadAccountPoolSource], never a second method on it: that one-method fun
 *  interface is implemented by test doubles tree-wide through SAM conversion (a bare lambda), and
 *  widening it would break every one of them for a capability only the real [splice.upstream.credentials.AccountPool]
 *  has. A route discovers this with a checked cast — the same idiom [splice.control.api.StatuslineRoute]
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

/** V4-132: the login/remove/relabel machinery — POST/GET /api/auth/{head}/login[/{id}] and
 *  DELETE/PATCH /api/auth/{head}/accounts/{label} (FEATURES.md §6). Running DeviceLoginFlow /
 *  OAuthLoginFlow and touching splice.app.auth.OAuthAccountFiles both live in :app; :control
 *  depends on :core only, so this port is how a route reaches them, assigned by ConsoleWiring
 *  exactly like every other [ConsolePorts] property. NULL MEANS UNWIRED: the routes answer a
 *  named 5xx, never a payload that reads as "no accounts". */
public interface ConsoleAccounts {
    public suspend fun startLogin(headKey: String, label: String?, restart: HeadRestart): LoginStart
    public fun pollLogin(id: String): LoginStatus?
    public suspend fun removeAccount(headKey: String, label: String): AccountMutation
    public suspend fun relabelAccount(headKey: String, label: String, newLabel: String): AccountMutation
}

/** Calls back into the head lifecycle once a login's credential has landed, so the account joins
 *  its pool (the row's "on landing the head restarts" contract). Bound to the resolved
 *  [ManagedHead] at the ROUTE, so the :app-side port implementation never needs its own copy of
 *  `Map<String, ManagedHead>` — the same reason [splice.control.api.HeadRoutes] calls
 *  `managed.head.restart()` directly rather than through a lookup. */
public fun interface HeadRestart {
    public suspend fun restart()
}

public enum class LoginState(public val wire: String) {
    STARTING("starting"),
    WAITING("waiting"),
    SIGNED_IN("signed_in"),
    LIVE_AFTER_RESTART("live_after_restart"),
    FAILED("failed"),
}

/** One login's off-request progress — what GET /api/auth/{head}/login/{id} polls. [userCode] and
 *  [verificationUri] are the device flow's announcement; [browserUrl] is the OAuth flow's, for
 *  the console to open (FEATURES.md §6). The row's "the state reads signed in, live after restart
 *  until then": [LoginState.SIGNED_IN] once the credential is persisted, [LoginState.LIVE_AFTER_RESTART]
 *  once the head has restarted and the account is confirmed in its pool. */
public data class LoginStatus(
    val id: String,
    val head: String,
    val state: LoginState,
    val userCode: String? = null,
    val verificationUri: String? = null,
    val browserUrl: String? = null,
    val failureReason: String? = null,
)

public sealed class LoginStart {
    public data class Started(val status: LoginStatus) : LoginStart()
    public data object UnknownHead : LoginStart()
    public data class UnsupportedAuthKind(val kind: String) : LoginStart()
}

public sealed class AccountMutation {
    public data object Ok : AccountMutation()
    public data object UnknownHead : AccountMutation()
    public data class Refused(val reason: String) : AccountMutation()
}
