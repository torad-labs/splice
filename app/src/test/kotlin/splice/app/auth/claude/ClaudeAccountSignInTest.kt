// NEW: "Sign in to another account" on a Claude head. The two rules a person's logins depend on: a sign-in for one
// head or label never writes another's folder, and adding an account never replaces an account already filed.
// No real sign-in runs here: the native child is a fixture, and every credential is synthetic.
package splice.app.auth.claude

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.client.wrap.WrapStateRead
import splice.core.auth.CredentialKey
import splice.core.util.WallClock
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Files
import java.nio.file.Path

private const val HEAD = "claude-splice"
private const val OTHER_HEAD = "claude"
private const val BROWSER = "https://claude.com/cai/oauth/authorize?fixture=allowed\n"

class ClaudeAccountSignInTest {
    @TempDir
    lateinit var state: Path

    private var clock = 1_800_000_000_000L

    private fun folders(): ClaudeAccountFolders = ClaudeAccountFolders(state, WallClock { clock })

    /** The sign-in under test, with a fixture child that writes [account] into whatever folder it is pointed at. */
    private fun signIn(scope: CoroutineScope, child: NativeLoginTestProcess, account: String?): ClaudeAccountSignIn {
        val auth = NativeClaudeAuth(
            WrapStateRead { "fixture-native" },
            emptyMap(),
            ProcessDispatchers().io(),
            NativeAuthStart { builder ->
                val dir = builder.environment()["CLAUDE_CONFIG_DIR"]?.let(Path::of)
                if (dir != null && account != null) write(dir, account)
                child
            },
        )
        return ClaudeAccountSignIn(folders(), auth, scope)
    }

    private fun write(directory: Path, account: String, token: String = "synthetic-$account") {
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$token","refreshToken":"r-$account","expiresAt":$FAR_FUTURE}}""",
        )
        Files.writeString(
            directory.resolve(".claude.json"),
            """{"oauthAccount":{"accountUuid":"$account","emailAddress":"$account@synthetic"}}""",
        )
        val key = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))
        ClaudeCredentialProfiles(state, {}).observed(key, ClaudeAccountIdentity(account, "$account@synthetic"))
    }

    /** Runs one sign-in to its end and returns its final status. */
    private fun add(head: String, label: String?, account: String?): LoginStatus = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val child = NativeLoginTestProcess(BROWSER)
        try {
            val started = signIn(scope, child, account).let { owner ->
                val status = owner.start(head, label)
                withTimeout(5_000) { while (owner.poll(status.id)?.state == LoginState.STARTING) yield() }
                child.finish(0)
                withTimeout(5_000) {
                    while (owner.poll(status.id)?.state == LoginState.WAITING) yield()
                }
                owner.poll(status.id)!!
            }
            clock += 1
            started
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private fun credentials(head: String, label: String): Path =
        state.resolve("claude-accounts").resolve(head).resolve(label).resolve(".credentials.json")

    @Test
    fun `a sign-in for one head and label writes only its own folder`() {
        assertEquals(LoginState.SIGNED_IN, add(HEAD, "work", "uuid-work").state)
        val before = Files.readString(credentials(HEAD, "work"))

        val other = add(OTHER_HEAD, "work", "uuid-other")

        assertEquals(LoginState.SIGNED_IN, other.state)
        assertEquals(before, Files.readString(credentials(HEAD, "work")), "the first head's login is untouched")
        assertTrue(Files.readString(credentials(OTHER_HEAD, "work")).contains("uuid-other".drop(5)))
        assertEquals(listOf("work"), folders().accounts(HEAD).map { it.label })
        assertEquals(listOf("work"), folders().accounts(OTHER_HEAD).map { it.label })
    }

    @Test
    fun `adding an account never replaces another account's login`() {
        add(HEAD, "work", "uuid-work")
        val before = Files.readString(credentials(HEAD, "work"))

        assertEquals(LoginState.SIGNED_IN, add(HEAD, "home", "uuid-home").state)

        assertEquals(before, Files.readString(credentials(HEAD, "work")), "byte for byte")
        val accounts = folders().accounts(HEAD)
        assertEquals(listOf("work", "home"), accounts.map { it.label }, "both, in the order they were added")
        assertEquals(listOf("uuid-work", "uuid-home"), accounts.map { it.identity?.uuid })
    }

    @Test
    fun `the same account under a new label is refused by the name it already has, and writes nothing`() {
        add(HEAD, "work", "uuid-work")

        val again = add(HEAD, "second", "uuid-work")

        assertEquals(LoginState.FAILED, again.state)
        assertEquals("that account is already on this command as 'work'", again.failureReason)
        assertEquals(listOf("work"), folders().accounts(HEAD).map { it.label }, "no second folder was filed")
        assertFalse(Files.exists(credentials(HEAD, "second")))
    }

    @Test
    fun `a sign-in that records no account fails with a reason and leaves no folder behind`() {
        val refused = add(HEAD, "work", account = null)

        assertEquals(LoginState.FAILED, refused.state)
        assertEquals("the sign-in recorded no account", refused.failureReason)
        assertEquals(emptyList<String>(), folders().accounts(HEAD).map { it.label })
        assertFalse(Files.exists(state.resolve("claude-accounts-pending").resolve(HEAD).resolve("work")))
    }

    @Test
    fun `a request with no label gets one that reads as the pool's next login`() {
        val minted = add(HEAD, label = null, account = "uuid-work")

        assertEquals(LoginState.SIGNED_IN, minted.state)
        assertEquals("account-2", minted.label, "the caller's own sign-in is the first login")
        assertEquals(listOf("account-2"), folders().accounts(HEAD).map { it.label })
        assertEquals("account-3", add(HEAD, label = null, account = "uuid-home").label)
    }

    @Test
    fun `a label that cannot name a folder is refused before any process starts`() {
        val refused = add(HEAD, "../escape", "uuid-work")

        assertEquals(LoginState.FAILED, refused.state)
        assertNotNull(refused.failureReason)
        assertEquals(emptyList<String>(), folders().accounts(HEAD).map { it.label })
    }

    @Test
    fun `the caller's own sign-in label is reserved, so no added account can take it`() {
        val refused = add(HEAD, "claude-code", "uuid-work")

        assertEquals(LoginState.FAILED, refused.state)
        assertTrue(refused.failureReason.orEmpty().contains("your own Claude Code sign-in"))
    }
}

private const val FAR_FUTURE = 4_000_000_000_000L
