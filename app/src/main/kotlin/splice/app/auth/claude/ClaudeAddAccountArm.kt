// NEW: the `client` arm of the add-an-account surface every other provider already has (operator ruling, Oct 3,
// 11:44 PM CT: a head can hold many subscriptions). POST /api/auth/{head}/login answers
// UnsupportedAuthKind("client") for a Claude head, because splice runs no OAuth flow for the caller's OWN login.
// It can run one for an account it ADDS: Claude Code's own `auth login` into a folder splice owns
// ([ClaudeAccountSignIn]).
//
// It is a decorator rather than a branch inside the generic port, so neither ConsoleWiring nor ControlPlane grows
// a method for it: both already carry more than their share. It intercepts exactly one answer, the refusal the
// generic port already reasoned its way to, so this file reads no topology of its own and cannot misfire on a head
// whose kind it guessed.
package splice.app.auth.claude

import splice.accounts.signin.ConsoleAccounts
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginStart
import splice.accounts.signin.LoginStatus
import splice.core.auth.CLIENT_AUTH_KIND

/** The sign-in this daemon runs for added Claude accounts, or null before the Claude arm is wired (a daemon with
 *  no Claude head). Read per call, because the port is assigned after the console's own wiring. */
internal fun interface ClaudeAddAccountSource {
    fun current(): ClaudeAccountSignIn?
}

internal class ClaudeAddAccountArm(
    private val generic: ConsoleAccounts,
    private val signIn: ClaudeAddAccountSource,
) : ConsoleAccounts by generic {

    /** A Claude head's add goes to its own sign-in; every other head's answer is the generic port's, untouched. */
    override suspend fun startLogin(headKey: String, label: String?, restart: HeadRestart): LoginStart {
        val answered = generic.startLogin(headKey, label, restart)
        if (answered !is LoginStart.UnsupportedAuthKind || answered.kind != CLIENT_AUTH_KIND) return answered
        val arm = signIn.current() ?: return answered
        return LoginStart.Started(arm.start(headKey, label, restart))
    }

    /** A Claude sign-in's id is known only to its own arm, and an OAuth id only to the generic port. */
    override fun pollLogin(id: String): LoginStatus? = signIn.current()?.poll(id) ?: generic.pollLogin(id)
}
