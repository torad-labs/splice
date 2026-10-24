// NEW: the `client` arm of the accounts port every other provider already has (operator ruling, Oct 3, 11:44 PM CT:
// a head can hold many subscriptions). The generic port serves a head whose accounts live in the OAuth account file.
// A Claude head's added accounts live in folders splice owns ([ClaudeAccountFolders]), so for that kind this arm
// answers two verbs: the add, through Claude Code's own `auth login` ([ClaudeAccountSignIn]), and the remove.
//
// An account that can be added and not removed is half a feature, which is why both verbs are here rather than only
// the add.
//
// It is a decorator rather than a branch inside the generic port, so neither ConsoleWiring nor ControlPlane grows a
// method for it: both already carry more than their share. It intercepts exactly the refusal the generic port
// already reasoned its way to, carrying the head's own auth kind, so this file reads no topology of its own and
// cannot misfire on a head whose kind it guessed.
package splice.app.auth.claude

import splice.accounts.signin.AccountMutation
import splice.accounts.signin.ConsoleAccounts
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginStart
import splice.accounts.signin.LoginStatus
import splice.core.auth.CLIENT_AUTH_KIND

/** What the arm needs from a daemon's Claude machinery: the sign-in that adds an account, and the folder store it
 *  removes from. One object so ControlPlane carries one field for the whole arm. */
internal data class ClaudeAccountsPort(
    val signIn: ClaudeAccountSignIn,
    val folders: ClaudeAccountFolders,
)

/** This daemon's Claude machinery, or null before the Claude arm is wired (a daemon with no Claude head). Read per
 *  call, because the port is assigned after the console's own wiring. */
internal fun interface ClaudeAccountsSource {
    fun current(): ClaudeAccountsPort?
}

internal class ClaudeAccountsArm(
    private val generic: ConsoleAccounts,
    private val claude: ClaudeAccountsSource,
) : ConsoleAccounts by generic {

    /** A Claude head's add goes to its own sign-in; every other head's answer is the generic port's, untouched. */
    override suspend fun startLogin(headKey: String, label: String?, restart: HeadRestart): LoginStart {
        val answered = generic.startLogin(headKey, label, restart)
        if (answered !is LoginStart.UnsupportedAuthKind || answered.kind != CLIENT_AUTH_KIND) return answered
        val port = claude.current() ?: return answered
        return LoginStart.Started(port.signIn.start(headKey, label, restart))
    }

    /** A Claude sign-in's id is known only to its own arm, and an OAuth id only to the generic port. */
    override fun pollLogin(id: String): LoginStatus? = claude.current()?.signIn?.poll(id) ?: generic.pollLogin(id)

    /** A stored Claude login's stable id stays put; only its display alias changes. */
    override suspend fun relabelAccount(headKey: String, label: String, newLabel: String): AccountMutation {
        val answered = generic.relabelAccount(headKey, label, newLabel)
        if (answered !is AccountMutation.UnsupportedAuthKind || answered.kind != CLIENT_AUTH_KIND) return answered
        return claude.current()?.folders?.relabel(headKey, label, newLabel) ?: answered
    }

    /** A Claude head's remove deletes one added account's own folder. The sentences are here rather than in the
     *  store because this is the surface a person reads, and each one says what was found rather than "failed". */
    override suspend fun removeAccount(headKey: String, label: String): AccountMutation {
        val answered = generic.removeAccount(headKey, label)
        if (answered !is AccountMutation.UnsupportedAuthKind || answered.kind != CLIENT_AUTH_KIND) return answered
        val port = claude.current() ?: return answered
        return when (port.folders.remove(headKey, label)) {
            ClaudeAccountRemoval.Removed -> AccountMutation.Ok
            ClaudeAccountRemoval.OwnSignIn -> AccountMutation.Refused(
                "'$label' is your own Claude Code sign-in, which splice forwards and never holds",
            )
            ClaudeAccountRemoval.NotFound ->
                AccountMutation.Refused("this command has no added account labeled '$label'")
            ClaudeAccountRemoval.Failed ->
                AccountMutation.Refused("'$label' could not be removed; its folder is still on disk")
        }
    }
}
