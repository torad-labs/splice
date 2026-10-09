// PORT-OF: daemon/control/.../HeadAccountPool.kt (V4-132) — the console's sign-in and account-edit port and
// its login vocabulary, split from the pool read model: the pool is read by every operator surface, this
// port is implemented by the app's login machinery and read only by the sign-in and edit routes.
package splice.accounts.signin

/** V4-132: the login/remove/relabel machinery — POST/GET /api/auth/{head}/login[/{id}] and
 *  DELETE/PATCH /api/auth/{head}/accounts/{label} (FEATURES.md §6). Running DeviceLoginFlow /
 *  OAuthLoginFlow and touching splice.app.auth.OAuthAccountFiles both live in :app, which this feature
 *  never depends on, so this port is how a route reaches them, assigned by ConsoleWiring exactly like
 *  every other console port. NULL MEANS UNWIRED: the routes answer a named 5xx, never a payload that
 *  reads as "no accounts". */
public interface ConsoleAccounts {
    public suspend fun startLogin(headKey: String, label: String?, restart: HeadRestart): LoginStart
    public fun pollLogin(id: String): LoginStatus?
    public suspend fun removeAccount(headKey: String, label: String): AccountMutation
    public suspend fun relabelAccount(headKey: String, label: String, newLabel: String): AccountMutation
}

/** Calls back into the head lifecycle once a login's credential has landed, so the account joins
 *  its pool (the row's "on landing the head restarts" contract). Bound to the resolved
 *  [splice.accounts.AccountHead] at the ROUTE, so the :app-side port implementation never needs its
 *  own copy of the daemon's head map — the same reason the heads feature restarts the head it
 *  resolved directly rather than through a lookup. */
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

/** One login's off-request progress — what GET /api/auth/{head}/login/{id} polls. [prompt] is the device
 *  flow's announcement or the OAuth flow's browser URL, for the console to open (FEATURES.md §6). The row's
 *  "the state reads signed in, live after restart until then": [LoginState.SIGNED_IN] once the credential
 *  is persisted, [LoginState.LIVE_AFTER_RESTART] once the head has restarted and the account is confirmed in
 *  its pool. */
public data class LoginStatus(
    val id: String,
    val head: String,
    val state: LoginState,
    val prompt: LoginPrompt = LoginPrompt(),
    val failureReason: String? = null,
    /** The label actually written, which can differ from an automatic request's candidate. */
    val label: String? = null,
    /** Archived quota path for a labeled renewal, null when no record was set aside. */
    val usageSetAside: String? = null,
)

public sealed class LoginStart {
    public data class Started(val status: LoginStatus) : LoginStart()
    public data object UnknownHead : LoginStart()
    public data class UnsupportedAuthKind(val kind: String) : LoginStart()
}

public sealed class AccountMutation {
    public data object Ok : AccountMutation()
    public data object UnknownHead : AccountMutation()

    /** The head exists and keeps its accounts somewhere the OAuth account file is not, so this port cannot edit
     *  them. It carries the head's own `auth.kind` for the same reason [LoginStart.UnsupportedAuthKind] does: an
     *  arm for that kind decorates this port and answers exactly this refusal, and a head that merely does not
     *  exist stays [UnknownHead]. Without the distinction a DELETE on a configured Claude head read as 404
     *  "unknown head" for a head the console had just drawn. */
    public data class UnsupportedAuthKind(val kind: String) : AccountMutation()

    public data class Refused(val reason: String) : AccountMutation()
}
