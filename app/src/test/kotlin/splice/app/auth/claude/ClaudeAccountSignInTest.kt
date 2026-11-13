// "Sign in to another account" on a Claude head. The two rules a person's logins depend on: a sign-in for one
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.AccountHead
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.pool.AccountsRoute
import splice.accounts.pool.HeadAccountAuthSource
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginFailure
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.core.auth.AccountRef
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
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

    /** The next sign-in's fixture child, and the account it writes into whatever folder it is pointed at. */
    @Volatile
    private var child: NativeLoginTestProcess? = null

    @Volatile
    private var account: String? = null

    private val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())

    /** One sign-in store for every [add] of a test, as a daemon has one. A sign-in on a label waits there for the
     *  one before it to let go of the label's pending folder. With a store of its own for each sign-in nothing
     *  ordered them: the first one's folder goes after its job is over, which is after a join on it answers, and it
     *  could take the folder the second had just written into. On Nov 13, 2026 main's CI read "expected:
     *  <OTHER_ACCOUNT> but was: <NOT_COMPLETED>" for a second sign-in on one label (verify jvm-1). */
    private val owner by lazy {
        val auth = NativeClaudeAuth(
            WrapStateRead { ClaudeToRun.Wrapped("fixture-native") },
            emptyMap(),
            ProcessDispatchers().io(),
            NativeAuthStart { builder ->
                val dir = builder.environment()["CLAUDE_CONFIG_DIR"]?.let(Path::of)
                val signedIn = account
                if (dir != null && signedIn != null) write(dir, signedIn)
                checkNotNull(child)
            },
        )
        ClaudeAccountSignIn(folders(), auth, scope)
    }

    @AfterEach
    fun stop() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }

    private fun write(directory: Path, account: String, token: String = "synthetic-$account") {
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$token","refreshToken":"r-$account","expiresAt":$FAR_FUTURE}}""",
        )
        Files.writeString(
            directory.resolve(".claude.json"),
            """{"oauthAccount":{"accountUuid":"$account","emailAddress":"person@example.invalid"}}""",
        )
        val key = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))
        ClaudeCredentialProfiles(state, {}).observed(key, ClaudeAccountIdentity(account, "person@example.invalid"))
    }

    /** Runs one sign-in to its end and returns its final status. */
    private fun add(head: String, label: String?, account: String?): LoginStatus = runBlocking {
        val next = NativeLoginTestProcess(BROWSER)
        child = next
        this@ClaudeAccountSignInTest.account = account
        val status = owner.start(head, label)
        withTimeout(5_000) { while (owner.poll(status.id)?.state == LoginState.STARTING) yield() }
        next.finish(0)
        withTimeout(5_000) { while (owner.poll(status.id)?.state == LoginState.WAITING) yield() }
        clock += 1
        owner.poll(status.id)!!
    }

    /** A sign-in store whose sign-ins each run the next of [children], open for [check] and torn down after it. */
    private fun open(
        account: String,
        children: List<NativeLoginTestProcess>,
        check: suspend (ClaudeAccountSignIn) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val next = children.iterator()
        val auth = NativeClaudeAuth(
            WrapStateRead { ClaudeToRun.Wrapped("fixture-native") },
            emptyMap(),
            ProcessDispatchers().io(),
            NativeAuthStart { builder ->
                write(Path.of(builder.environment().getValue("CLAUDE_CONFIG_DIR")), account)
                next.next()
            },
        )
        try {
            check(ClaudeAccountSignIn(folders(), auth, scope))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private suspend fun waiting(owner: ClaudeAccountSignIn, id: String) =
        withTimeout(5_000) { while (owner.poll(id)?.state != LoginState.WAITING) yield() }

    @Test
    fun `a cancelled sign-in reads cancelled, stops Claude Code and leaves nothing behind`() {
        val child = NativeLoginTestProcess(BROWSER)
        open("uuid-work", listOf(child)) { owner ->
            val started = owner.start(HEAD, "work")
            waiting(owner, started.id)

            val ended = owner.cancel(started.id)

            assertEquals(LoginFailure.CANCELLED, ended?.failureKind)
            assertFalse(child.isAlive, "Claude Code's sign-in is stopped")
            assertEquals(emptyList<LoginStatus>(), owner.open())
            assertFalse(Files.exists(state.resolve("claude-accounts-pending").resolve(HEAD).resolve("work")))
            assertEquals(emptyList<String>(), folders().accounts(HEAD).map { it.label })
        }
    }

    // Marcos, Nov 11, 2026: "then trhe sign in in splice showed still in progress". The link Claude Code prints ends
    // in a code to paste back, and only a command's own login card had the field for it.
    @Test
    fun `a sign-in waiting on Claude Code's page says its code is pasted back, whichever card started it`() {
        val child = NativeLoginTestProcess(BROWSER)
        open("uuid-work", listOf(child)) { owner ->
            val started = owner.start(HEAD, "work")
            waiting(owner, started.id)

            val prompt = owner.poll(started.id)!!.prompt

            assertTrue(prompt.pasteBack)
            assertEquals(BROWSER.trim(), prompt.browserUrl)
            owner.cancel(started.id)
        }
    }

    // Marcos, Nov 11, 2026: "it said it canceled, then when I tried sign in again, it said it was already in progress".
    @Test
    fun `signing in again on a login whose sign-in is still open replaces it, and the new one lands`() {
        val first = NativeLoginTestProcess(BROWSER)
        val second = NativeLoginTestProcess(BROWSER)
        open("uuid-work", listOf(first, second)) { owner ->
            val earlier = owner.start(HEAD, "work")
            waiting(owner, earlier.id)

            val again = owner.start(HEAD, "work")
            waiting(owner, again.id)

            assertEquals(LoginFailure.CANCELLED, owner.poll(earlier.id)?.failureKind)
            assertFalse(first.isAlive, "the earlier sign-in's Claude Code is stopped")
            second.finish(0)
            withTimeout(5_000) { while (owner.poll(again.id)?.state == LoginState.WAITING) yield() }
            assertEquals(LoginState.SIGNED_IN, owner.poll(again.id)?.state)
            assertEquals(listOf("work"), folders().accounts(HEAD).map { it.label })
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

    // The browser's own session decides which account a sign-in is for, so Sign in again on one login's card can
    // come back as another account. It read "Config error", with no Try again.
    @Test
    fun `signing in again on a login with another account says so, and the login keeps its own`() {
        add(HEAD, "work", "uuid-work")
        val before = Files.readString(credentials(HEAD, "work"))

        val refused = add(HEAD, "work", "uuid-home")

        assertEquals(LoginState.FAILED, refused.state)
        assertEquals(LoginFailure.OTHER_ACCOUNT, refused.failureKind)
        assertEquals(before, Files.readString(credentials(HEAD, "work")), "byte for byte")
        assertEquals(listOf("uuid-work"), folders().accounts(HEAD).map { it.identity?.uuid })
    }

    @Test
    fun `the same account under a new label is refused by the name it already has, and writes nothing`() {
        add(HEAD, "work", "uuid-work")

        val again = add(HEAD, "second", "uuid-work")

        assertEquals(LoginState.FAILED, again.state)
        val said = "that account is already on this command as 'work', so nothing was added. " +
            "If that login was blocked, its next turn asks the provider again"
        assertEquals(said, again.failureReason)
        assertEquals(LoginFailure.ALREADY_ADDED, again.failureKind)
        assertEquals("work", again.label, "the console pulses the card already there by this label")
        assertEquals(listOf("work"), folders().accounts(HEAD).map { it.label }, "no second folder was filed")
        assertFalse(Files.exists(credentials(HEAD, "second")))
    }

    @Test
    fun `a sign-in that records no account fails with a reason and leaves no folder behind`() {
        val refused = add(HEAD, "work", account = null)

        assertEquals(LoginState.FAILED, refused.state)
        assertEquals("the sign-in recorded no account", refused.failureReason)
        assertEquals(LoginFailure.NOT_COMPLETED, refused.failureKind)
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
        assertEquals(LoginFailure.CONFIG, refused.failureKind)
        assertEquals(emptyList<String>(), folders().accounts(HEAD).map { it.label })
    }

    @Test
    fun `the caller's own sign-in label is reserved, so no added account can take it`() {
        val refused = add(HEAD, "claude-code", "uuid-work")

        assertEquals(LoginState.FAILED, refused.state)
        assertTrue(refused.failureReason.orEmpty().contains("your own Claude Code sign-in"))
    }

    // Account naming reads chosen labels first and the credential's verified email before its plan fallback.
    private fun roster(label: String, plan: String): JsonObject = runBlocking {
        val folder = state.resolve("claude-accounts").resolve(HEAD).resolve(label)
        val described = ClaudeFolderAuth(
            folder,
            refresh = { RefreshAttempt.Denied("not asked in this test") },
            profiles = ClaudeCredentialProfiles(state, {}),
        ).describe()
        val head = AccountHead(
            key = HEAD,
            auth = object : AuthProvider {
                override suspend fun credentials(): Credentials = Credentials.ClientForwarded
                override suspend fun describe() = AuthDescription(true, "client")
            },
            pool = HeadAccountPoolSource {
                HeadAccountPoolView(null, listOf(HeadAccountView(label, false, false, true, plan)), null)
            },
            accountAuth = HeadAccountAuthSource { mapOf(label to described) },
            restart = HeadRestart { },
        )
        val body = AccountsRoute(mapOf(HEAD to head)).accountsJson()
        Json.parseToJsonElement(body).jsonObject.getValue("accounts").jsonArray.single().jsonObject
    }

    @Test
    fun `a login added with a label is shown by that label`() {
        assertEquals(LoginState.SIGNED_IN, add(HEAD, label = "work", account = "uuid-work").state)
        val row = roster("work", "max")
        assertEquals("work", row.getValue("display_name").jsonPrimitive.content)
        assertEquals("false", row.getValue("label_default").jsonPrimitive.content)
        assertEquals(AccountRef.of("uuid-work"), row.getValue("ref").jsonPrimitive.content)
    }

    @Test
    fun `a lone login added with no label defaults to its email, with its ref kept for naming, never the id`() {
        val minted = add(HEAD, label = null, account = "uuid-home")
        val row = roster(minted.label!!, "max")
        assertEquals("person@example.invalid", row.getValue("display_name").jsonPrimitive.content)
        assertEquals(AccountRef.of("uuid-home"), row.getValue("ref").jsonPrimitive.content)
        assertEquals("true", row.getValue("label_default").jsonPrimitive.content, "a default is offered its one naming")
        assertFalse(row.toString().contains("uuid-home"), row.toString())
    }
}

private const val FAR_FUTURE = 4_000_000_000_000L
