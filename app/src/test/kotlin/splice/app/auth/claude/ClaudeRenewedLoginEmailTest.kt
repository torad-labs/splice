// Marcos, Nov 3, 2026: "WE always had account emails for all accounts, now claude accounts dont all show the email,
// this is a recent regression". That day five Claude cards went two to four hours without their email: splice renewed
// each login's token between 1:47 and 3:32 PM CT, the email was filed under the old token, and the new token was
// proved only when the Accounts page next read the login, at 5:58 PM. The goal, as the person at the console sees it:
// a Claude login keeps its email through every token renewal, and a card names only the account its own token proves.
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

private const val HEAD = "claude-splice"
private const val LABEL = "account-2"
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private val TORAD = ClaudeAccountIdentity("uuid-torad", "marcos@torad.example")
private val SEEK = ClaudeAccountIdentity("uuid-seek", "marcos@seekapps.example")

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

    private fun key(token: String): String =
        requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))

    /** The login's folder as a sign-in files it: its record, then its credential, expiring [expiresInMs] from now. */
    private fun file(token: String, expiresInMs: Long) {
        val folder = Files.createDirectories(ClaudeAccountPaths(paths.stateDir).account(HEAD, LABEL))
        Files.writeString(folder.resolve(".splice-account.json"), ClaudeAccountRecord(1L).wire())
        Files.writeString(
            folder.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$token","refreshToken":"refresh-$token",""" +
                """"expiresAt":${System.currentTimeMillis() + expiresInMs},""" +
                """"scopes":["user:inference","user:profile"],"subscriptionType":"max"}}""",
        )
    }

    /** What an earlier profile call proved for [token], as the daemon's own store keeps it. */
    private fun proved(token: String, account: ClaudeAccountIdentity) =
        ClaudeCredentialProfiles(paths.stateDir, {}).observed(key(token), account)

    /** The added login as a daemon wires it, on a runtime whose profile calls go to [profile]. */
    private fun TestScope.login(profile: ClaudeProfileCall, refresh: ClaudeTokenRefresh): AuthProvider {
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

    private fun shows(account: ClaudeAccountIdentity) = account.email to AccountRef.of(account.uuid)

    @Test
    fun `a login splice renews keeps its email on the next read and after a restart, with no profile answer`() =
        runTest {
            file("old", expiresInMs = MINUTE_MS) // inside the five-minute window: the next turn renews it
            proved("old", TORAD)
            val login = login(silent, renew)
            assertEquals(shows(TORAD), card(login), "before the renewal")

            assertEquals(Credentials.Bearer("renewed"), login.credentials(), "a turn renews the token")
            runCurrent()
            assertEquals(shows(TORAD), card(login), "the first read after the renewal")

            val restarted = login(silent, noRenewal)
            assertEquals(shows(TORAD), card(restarted), "after a daemon restart")
        }

    @Test
    fun `a renewed login whose old token was never proved has its email on the first read after the renewal`() =
        runTest {
            file("old", expiresInMs = MINUTE_MS)
            val profile = ClaudeProfileCall { token -> if (token == "renewed") SEEK else awaitCancellation() }
            val login = login(profile, renew)

            login.credentials()
            runCurrent() // the renewal's moment passes, and nobody has opened the Accounts page yet
            assertEquals(shows(SEEK), card(login), "the first read after the renewal")
        }

    @Test
    fun `a token another sign-in wrote shows no earlier account's email, then the account it proves`() = runTest {
        file("old", expiresInMs = 8 * HOUR_MS)
        proved("old", TORAD)
        val answer = CompletableDeferred<Unit>()
        val login = login(
            ClaudeProfileCall { token ->
                if (token != "signed-in") awaitCancellation()
                answer.await()
                SEEK
            },
            noRenewal,
        )
        assertEquals(shows(TORAD), card(login), "before the new sign-in")

        file("signed-in", expiresInMs = 8 * HOUR_MS) // Sign in again on this card, as another account
        runCurrent()
        assertEquals(null to null, card(login), "while unproved, never the earlier account's email")

        answer.complete(Unit)
        runCurrent()
        assertEquals(shows(SEEK), card(login), "once proved, the account the new token belongs to")
    }
}

/** The caller's own sign-in, which no test here reads: the pool's first entry. */
private object OwnSignIn : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials? = null

    override suspend fun refresh(): Credentials? = null

    override suspend fun describe(): AuthDescription = AuthDescription(present = false, kind = "client")
}
