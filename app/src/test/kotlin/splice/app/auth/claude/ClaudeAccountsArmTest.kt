// NEW: the `client` arm of the accounts port. A Claude head's add reaches its own sign-in and its remove deletes
// that account's own folder; every other head's answer is the generic port's, unchanged. No real sign-in and no real
// head run here, and every credential written is synthetic.
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
import java.nio.file.Files
import java.nio.file.Path

private const val CLAUDE_HEAD = "claude-splice"
private const val OAUTH_HEAD = "codex"
private val NO_RESTART = HeadRestart { }

class ClaudeAccountsArmTest {
    @TempDir
    lateinit var state: Path

    /** The generic port as the Claude head finds it: a refusal by kind on both verbs, and an OAuth head it serves
     *  itself. The kind is what the arm dispatches on, which is why the refusal carries it. */
    private class Generic : ConsoleAccounts {
        var asked: String? = null
        var removed: Pair<String, String>? = null
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

        override suspend fun removeAccount(headKey: String, label: String): AccountMutation {
            removed = headKey to label
            return if (headKey == CLAUDE_HEAD) AccountMutation.UnsupportedAuthKind("client") else AccountMutation.Ok
        }

        override suspend fun relabelAccount(headKey: String, label: String, newLabel: String): AccountMutation =
            AccountMutation.Ok
    }

    private fun folders(): ClaudeAccountFolders = ClaudeAccountFolders(state)

    /** Files one synthetic account on [head] the way a finished sign-in does, so remove has something to delete. */
    private fun added(head: String, label: String) {
        val pending = folders().pending(head, label)
        Files.createDirectories(pending.directory)
        Files.writeString(
            pending.directory.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"synthetic-$label","refreshToken":"synthetic-r",""" +
                """"expiresAt":9000000000000}}""",
        )
        Files.writeString(
            pending.directory.resolve(".claude.json"),
            """{"oauthAccount":{"accountUuid":"uuid-$label"}}""",
        )
        assertTrue(folders().land(pending) is ClaudeAccountLanding.Added, "$label landed")
    }

    private fun arm(scope: CoroutineScope, generic: Generic, wired: Boolean): ClaudeAccountsArm {
        val port = ClaudeAccountsPort(
            ClaudeAccountSignIn(
                folders(),
                NativeClaudeAuth(
                    WrapStateRead { "fixture-native" },
                    emptyMap(),
                    ProcessDispatchers().io(),
                    NativeAuthStart { NativeLoginTestProcess() },
                ),
                scope,
            ),
            folders(),
        )
        return ClaudeAccountsArm(generic, ClaudeAccountsSource { port.takeIf { wired } })
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
            val removed = arm(scope, generic, wired = true).removeAccount(OAUTH_HEAD, "work")

            assertSame(generic.oauthStatus, (started as LoginStart.Started).status)
            assertEquals(AccountMutation.Ok, removed)
            assertEquals(OAUTH_HEAD to "work", generic.removed, "and the port is the one that did it")
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `a daemon with no Claude arm wired keeps the refusal by kind`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        try {
            val arm = arm(scope, Generic(), wired = false)
            val answered = arm.startLogin(CLAUDE_HEAD, "work", NO_RESTART)
            val removed = arm.removeAccount(CLAUDE_HEAD, "work")

            assertTrue(answered is LoginStart.UnsupportedAuthKind, "no invented flow: $answered")
            assertEquals("client", (answered as LoginStart.UnsupportedAuthKind).kind)
            assertTrue(removed is AccountMutation.UnsupportedAuthKind, "and no invented delete: $removed")
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

    @Test
    fun `a Claude head's remove deletes that account's own folder`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        added(CLAUDE_HEAD, "work")
        added(CLAUDE_HEAD, "personal")
        try {
            val removed = arm(scope, Generic(), wired = true).removeAccount(CLAUDE_HEAD, "work")

            assertEquals(AccountMutation.Ok, removed)
            assertEquals(listOf("personal"), folders().accounts(CLAUDE_HEAD).map { it.label })
            assertNull(folders().token(CLAUDE_HEAD, "work"), "and its credential is gone with it")
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `the caller's own sign-in and a label this head never had are each refused in words`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        added(CLAUDE_HEAD, "work")
        val arm = arm(scope, Generic(), wired = true)
        try {
            val own = arm.removeAccount(CLAUDE_HEAD, OWN_SIGN_IN_LABEL)
            val absent = arm.removeAccount(CLAUDE_HEAD, "never-added")

            assertEquals(
                "'claude-code' is your own Claude Code sign-in, which splice forwards and never holds",
                (own as AccountMutation.Refused).reason,
            )
            assertEquals(
                "this command has no added account labeled 'never-added'",
                (absent as AccountMutation.Refused).reason,
            )
            assertEquals(listOf("work"), folders().accounts(CLAUDE_HEAD).map { it.label }, "nothing was deleted")
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
