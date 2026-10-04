// NEW: the `client` arm of POST /api/auth/{head}/login. A Claude head's add reaches its own sign-in; every other
// head's answer is the generic port's, unchanged. No real sign-in and no real head run here.
package splice.app.auth.claude

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.signin.AccountMutation
import splice.accounts.signin.ConsoleAccounts
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginStart
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.client.wrap.WrapStateRead
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path

private const val CLAUDE_HEAD = "claude-splice"
private const val OAUTH_HEAD = "codex"
private val NO_RESTART = HeadRestart { }

class ClaudeAddAccountArmTest {
    @TempDir
    lateinit var state: Path

    /** The generic port as the Claude head finds it: a refusal by kind, and an OAuth head it serves itself. */
    private class Generic : ConsoleAccounts {
        var asked: String? = null
        val oauthStatus = LoginStatus("oauth-id", OAUTH_HEAD, LoginState.WAITING)

        override suspend fun startLogin(headKey: String, label: String?, restart: HeadRestart): LoginStart {
            asked = headKey
            return if (headKey == CLAUDE_HEAD) {
                LoginStart.UnsupportedAuthKind("client")
            } else {
                LoginStart.Started(oauthStatus)
            }
        }

        override fun pollLogin(id: String): LoginStatus? = oauthStatus.takeIf { id == it.id }

        override suspend fun removeAccount(headKey: String, label: String): AccountMutation = AccountMutation.Ok

        override suspend fun relabelAccount(headKey: String, label: String, newLabel: String): AccountMutation =
            AccountMutation.Ok
    }

    private fun arm(scope: CoroutineScope, generic: Generic, wired: Boolean): ClaudeAddAccountArm {
        val signIn = ClaudeAccountSignIn(
            ClaudeAccountFolders(state),
            NativeClaudeAuth(
                WrapStateRead { "fixture-native" },
                emptyMap(),
                ProcessDispatchers().io(),
                NativeAuthStart { NativeLoginTestProcess() },
            ),
            scope,
        )
        return ClaudeAddAccountArm(generic, ClaudeAddAccountSource { signIn.takeIf { wired } })
    }

    @Test
    fun `a Claude head's add reaches its own sign-in instead of the refusal by kind`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val generic = Generic()
        try {
            val started = arm(scope, generic, wired = true).startLogin(CLAUDE_HEAD, "work", NO_RESTART)

            assertTrue(started is LoginStart.Started, "a Claude head can add an account: $started")
            val status = (started as LoginStart.Started).status
            assertEquals(CLAUDE_HEAD, status.head)
            assertEquals("work", status.label)
            assertEquals(CLAUDE_HEAD, generic.asked, "the generic port still decided the kind")
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `every other head's answer is the generic port's, untouched`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val generic = Generic()
        try {
            val started = arm(scope, generic, wired = true).startLogin(OAUTH_HEAD, null, NO_RESTART)

            assertSame(generic.oauthStatus, (started as LoginStart.Started).status)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `a daemon with no Claude arm wired keeps the refusal by kind`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        try {
            val answered = arm(scope, Generic(), wired = false).startLogin(CLAUDE_HEAD, "work", NO_RESTART)

            assertTrue(answered is LoginStart.UnsupportedAuthKind, "no invented flow: $answered")
            assertEquals("client", (answered as LoginStart.UnsupportedAuthKind).kind)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `each id is polled by the arm that minted it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val generic = Generic()
        val arm = arm(scope, generic, wired = true)
        try {
            val claude = (arm.startLogin(CLAUDE_HEAD, "work", NO_RESTART) as LoginStart.Started).status

            assertEquals(CLAUDE_HEAD, arm.pollLogin(claude.id)?.head, "the Claude sign-in's own id")
            assertSame(generic.oauthStatus, arm.pollLogin("oauth-id"), "and an OAuth id still reaches the port")
            assertNull(arm.pollLogin("nobody's-id"))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
