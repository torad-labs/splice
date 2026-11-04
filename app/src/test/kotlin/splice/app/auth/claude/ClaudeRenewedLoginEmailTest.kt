// Marcos, Nov 3, 2026: "WE always had account emails for all accounts, now claude accounts dont all show the email,
// this is a recent regression". That day five Claude cards went two to four hours without their email: splice renewed
// each login's token between 1:47 and 3:32 PM CT, the email was filed under the old token, and the new token was
// proved only when the Accounts page next read the login, at 5:58 PM. The goal, as the person at the console sees it:
// a Claude login keeps its email through every token renewal, shows the email Anthropic reports for the account now,
// and a card names only the account its own token proves.
//
// The login runs as the daemon wires an added account (ClaudeAccountWiring over a temporary state folder) and is read
// the way the Accounts route reads it, through its describe. Two things stand in for Anthropic, which cannot run here:
// the token endpoint that renews a refresh token, and the profile call that proves which account a token belongs to.
@file:OptIn(ExperimentalCoroutinesApi::class)

package splice.app.auth.claude

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.app.provider.ClaudeAccountWiring
import splice.core.auth.ACCOUNT_REF_FIELD
import splice.core.auth.AccountRef
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.StatePaths
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

private const val HEAD = "claude-splice"
private const val LABEL = "account-2"
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val RUNS = 200
private const val STEPS = 12
private val TORAD = ClaudeAccountIdentity("uuid-torad", "marcos@torad.example")
private val SEEK = ClaudeAccountIdentity("uuid-seek", "marcos@seekapps.example")
private val NOTHING: Pair<String?, String?> = null to null

class ClaudeRenewedLoginEmailTest {
    @TempDir
    lateinit var tmp: Path

    private val paths by lazy { StatePaths(baseOverride = tmp.resolve("state")) }

    /** Anthropic's token endpoint renewing the login's refresh token. */
    private val renew = ClaudeTokenRefresh {
        ClaudeRefreshedTokens("renewed", "refresh-renewed", System.currentTimeMillis() + 8 * HOUR_MS)
    }

    private val noRenewal = ClaudeTokenRefresh { error("a live token is not renewed") }

    /** Anthropic's profile endpoint not answering at all. */
    private val silent = ClaudeProfileCall { awaitCancellation() }

    /** What an earlier profile call proved for [token], as the daemon's own store keeps it. */
    private fun proved(token: String, account: ClaudeAccountIdentity) =
        ClaudeCredentialProfiles(paths.stateDir, {}).observed(key(token), account)

    @Test
    fun `a login splice renews keeps its email on the next read and after a restart, with no profile answer`() =
        runTest {
            fileLogin(paths, "old", expiresInMs = MINUTE_MS) // inside the five-minute window: the next turn renews it
            proved("old", TORAD)
            val login = wire(paths, silent, renew)
            assertEquals(shows(TORAD), card(login), "before the renewal")

            assertEquals(Credentials.Bearer("renewed"), login.credentials(), "a turn renews the token")
            runCurrent()
            assertEquals(shows(TORAD), card(login), "the first read after the renewal")

            val restarted = wire(paths, silent, noRenewal)
            assertEquals(shows(TORAD), card(restarted), "after a daemon restart")
        }

    @Test
    fun `a renewal shows the email Anthropic reports for the account now`() = runTest {
        fileLogin(paths, "old", expiresInMs = MINUTE_MS)
        proved("old", TORAD)
        val moved = ClaudeAccountIdentity(TORAD.uuid, "marcos@torad-new.example")
        val profile = ClaudeProfileCall { token -> if (token == "renewed") moved else awaitCancellation() }
        val login = wire(paths, profile, renew)

        login.credentials()
        runCurrent()
        assertEquals(shows(moved), card(login), "the account's address as its profile gives it after the renewal")
    }

    @Test
    fun `a renewed login whose old token was never proved has its email on the first read after the renewal`() =
        runTest {
            fileLogin(paths, "old", expiresInMs = MINUTE_MS)
            val profile = ClaudeProfileCall { token -> if (token == "renewed") SEEK else awaitCancellation() }
            val login = wire(paths, profile, renew)

            login.credentials()
            runCurrent() // the renewal's moment passes, and nobody has opened the Accounts page yet
            assertEquals(shows(SEEK), card(login), "the first read after the renewal")
        }

    @Test
    fun `a token another sign-in wrote shows no earlier account's email, then the account it proves`() = runTest {
        fileLogin(paths, "old", expiresInMs = 8 * HOUR_MS)
        proved("old", TORAD)
        val answer = CompletableDeferred<Unit>()
        val login = wire(
            paths,
            ClaudeProfileCall { token ->
                if (token != "signed-in") awaitCancellation()
                answer.await()
                SEEK
            },
            noRenewal,
        )
        assertEquals(shows(TORAD), card(login), "before the new sign-in")

        fileLogin(paths, "signed-in", expiresInMs = 8 * HOUR_MS) // Sign in again on this card, as another account
        runCurrent()
        assertEquals(NOTHING, card(login), "while unproved, never the earlier account's email")

        answer.complete(Unit)
        runCurrent()
        assertEquals(shows(SEEK), card(login), "once proved, the account the new token belongs to")
    }

    @Test
    fun `over any run of renewals, sign-ins, restarts and profile answers, a card names only its live account`() =
        runTest {
            repeat(RUNS) { seed ->
                val random = Random(seed)
                val world = LoginWorld(this, StatePaths(baseOverride = tmp.resolve("run-$seed")))
                world.signIn(TORAD)
                repeat(STEPS) { step ->
                    val before = world.card()
                    val action = random.nextInt(4)
                    when (action) {
                        0 -> world.renew()
                        1 -> world.signIn(if (random.nextBoolean()) TORAD else SEEK)
                        2 -> world.restart()
                        else -> world.answers = Answer.entries.random(random)
                    }
                    runCurrent()
                    val shown = world.card()
                    val at = "seed $seed step $step action $action: $before then $shown, live ${world.owner.email}"
                    assertTrue(shown == NOTHING || shown == shows(world.owner), at)
                    if (action == 0 && before == shows(world.owner)) {
                        assertEquals(before, shown, "a renewal lost it; $at")
                    }
                }
            }
        }
}

/** How Anthropic's profile endpoint answers in a generated run. */
private enum class Answer { PROVES, REFUSES, SILENT }

/** One login folder over a run of actions, with a model of which account its live token was issued for ([owner]).
 *  Tokens are numbered as Anthropic's token endpoint issues them, each recorded with its account, so a renewal hands
 *  out a token of the account whose refresh token it spent and the profile stand-in proves each token's own account. */
private class LoginWorld(private val test: TestScope, private val paths: StatePaths) {
    private val accounts = mutableMapOf<String, ClaudeAccountIdentity>()
    private var issued = 0
    private var token = ""
    private var login: AuthProvider? = null
    var answers = Answer.PROVES
    var owner: ClaudeAccountIdentity = TORAD
        private set

    private val profile = ClaudeProfileCall { token ->
        when (answers) {
            Answer.PROVES -> accounts[token]
            Answer.REFUSES -> null
            Answer.SILENT -> awaitCancellation()
        }
    }

    private val renewal = ClaudeTokenRefresh { refresh ->
        val next = issue(accounts.getValue(refresh.removePrefix("refresh-")))
        ClaudeRefreshedTokens(next, "refresh-$next", System.currentTimeMillis() + 8 * HOUR_MS)
    }

    private fun issue(account: ClaudeAccountIdentity): String = "token-${++issued}".also { accounts[it] = account }

    /** A sign-in on this card files a live token of [account]. */
    fun signIn(account: ClaudeAccountIdentity) {
        owner = account
        token = issue(account)
        fileLogin(paths, token, expiresInMs = 8 * HOUR_MS)
    }

    /** Time passes until the live token is inside its renewal window, and the next turn renews it. */
    suspend fun renew() {
        fileLogin(paths, token, expiresInMs = MINUTE_MS)
        token = (current().credentials() as Credentials.Bearer).token
    }

    fun restart() {
        login = null
    }

    suspend fun card(): Pair<String?, String?> = card(current())

    private fun current(): AuthProvider = login ?: test.wire(paths, profile, renewal).also { login = it }
}

private fun key(token: String): String =
    requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))

/** The login's folder as a sign-in files it: its record, then its credential, expiring [expiresInMs] from now. */
private fun fileLogin(paths: StatePaths, token: String, expiresInMs: Long) {
    val folder = Files.createDirectories(ClaudeAccountPaths(paths.stateDir).account(HEAD, LABEL))
    Files.writeString(folder.resolve(".splice-account.json"), ClaudeAccountRecord(1L).wire())
    Files.writeString(
        folder.resolve(".credentials.json"),
        """{"claudeAiOauth":{"accessToken":"$token","refreshToken":"refresh-$token",""" +
            """"expiresAt":${System.currentTimeMillis() + expiresInMs},""" +
            """"scopes":["user:inference","user:profile"],"subscriptionType":"max"}}""",
    )
}

/** The added login as a daemon wires it, on a runtime whose profile calls go to [profile]. */
private fun TestScope.wire(paths: StatePaths, profile: ClaudeProfileCall, refresh: ClaudeTokenRefresh): AuthProvider {
    val identities = ClaudeIdentityRefresh(
        backgroundScope,
        StandardTestDispatcher(testScheduler),
        ClaudeCredentialProfiles(paths.stateDir, {}),
        profile,
        {},
    )
    val wiring = ClaudeAccountWiring(paths, LogSink { }, refresh = refresh, identities = identities)
    return wiring.accounts(HEAD, OwnSignIn).single { it.label == LABEL }.auth
}

/** The email and short id the login's card shows, as the Accounts route reads them. */
private suspend fun card(login: AuthProvider): Pair<String?, String?> =
    login.describe().fields.let { it["email"] to it[ACCOUNT_REF_FIELD] }

private fun shows(account: ClaudeAccountIdentity): Pair<String?, String?> = account.email to AccountRef.of(account.uuid)

/** The caller's own sign-in, which no test here reads: the pool's first entry. */
private object OwnSignIn : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials? = null

    override suspend fun refresh(): Credentials? = null

    override suspend fun describe(): AuthDescription = AuthDescription(present = false, kind = "client")
}
