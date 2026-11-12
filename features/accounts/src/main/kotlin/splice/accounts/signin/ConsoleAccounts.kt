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

    /** Ends the sign-in [id] where it stands: whatever ran for it is stopped, and what it held (a label, a callback
     *  port, a pending folder) is free for the next one. Answers how it ended, which is its own end when it had
     *  already finished, and null for an id this daemon never started. */
    public suspend fun cancelLogin(id: String): LoginStatus?

    /** Every sign-in still starting or waiting, so a page opened after one began shows it and can end it. */
    public fun openLogins(): List<LoginStatus>

    /** Removes the login [label] from [headKey], or gives it the label [newLabel]. [restart] is the head's own, for a
     *  head that only finds its logins when it starts: once the login is gone or renamed on disk, the head starts
     *  again so the running command lets go of it too. */
    public suspend fun removeAccount(headKey: String, label: String, restart: HeadRestart): AccountMutation
    public suspend fun relabelAccount(
        headKey: String,
        label: String,
        newLabel: String,
        restart: HeadRestart,
    ): AccountMutation
}

/** Calls back into the head lifecycle once a login's credential has landed, so the account joins
 *  its pool (the row's "on landing the head restarts" contract), and once a login was removed or
 *  renamed, so the pool lets go of it. Bound to the resolved
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
    val failure: FailedLogin? = null,
    /** The label actually written, which can differ from an automatic request's candidate. */
    val label: String? = null,
) {
    public val failureReason: String? get() = failure?.reason
    public val failureKind: LoginFailure? get() = failure?.kind

    /** Enough of [id] to tell two sign-ins apart in a log line, as a commit is named by its first characters. */
    public val shortId: String get() = id.take(SHORT_ID)

    /** Whether it is still starting, or waiting on the person: the states a cancel or a newer sign-in ends. */
    public val open: Boolean get() = state == LoginState.STARTING || state == LoginState.WAITING

    /** This login, failed for [reason], which is of [kind]. */
    public fun failed(kind: LoginFailure, reason: String): LoginStatus =
        copy(state = LoginState.FAILED, failure = FailedLogin(kind, reason))

    /** This login ended for [reason] when it was still open; one that already ended keeps its own end. */
    public fun closed(kind: LoginFailure, reason: String): LoginStatus = if (open) failed(kind, reason) else this
}

// why: eight characters of a random id tell apart the few sign-ins one daemon's log holds, and read at a glance.
private const val SHORT_ID = 8

/** Why a login failed: its [kind], which a surface names, and splice's own sentence, which the log keeps. */
public data class FailedLogin(val kind: LoginFailure, val reason: String)

/** Why a sign-in failed, one word per reason a surface shows. The network, a file and the config can fail any flow,
 *  and any flow can be cancelled; the rest come only from running Claude Code's own sign-in. A file or config failure
 *  fails again until it is fixed. */
public enum class LoginFailure(public val wire: String) {
    NOT_COMPLETED("not_completed"),
    NETWORK("network"),
    FILE("file"),
    CONFIG("config"),

    /** Claude Code's sign-in ran past its time. */
    EXPIRED("expired"),

    /** Claude Code's sign-in process stopped on its own. */
    STOPPED("stopped"),

    /** Ended before it finished: by the person, by a newer sign-in to the same place, or by the daemon stopping. */
    CANCELLED("cancelled"),

    /** The account signed into is already one of this command's. */
    ALREADY_ADDED("already_added"),

    /** Signed in again on a login, with another account than the one that login holds. Nothing was replaced. */
    OTHER_ACCOUNT("other_account"),
}

/** What a sign-in asks the person to do. [userCode] and [verificationUri] are the device flow's announcement;
 *  [browserUrl] is the OAuth flow's, for the console to open. A flow fills only the part it has. */
public data class LoginPrompt(
    val userCode: String? = null,
    val verificationUri: String? = null,
    val browserUrl: String? = null,
    /** The page at [browserUrl] ends by showing a code, which the person pastes back where they pressed Sign in
     *  (Claude Code's own sign-in). The console draws the field for it from this, on every card that signs in. */
    val pasteBack: Boolean = false,
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
