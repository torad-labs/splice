// Marcos, Nov 3, 2026: "now claude accounts dont all show the email, this is a recent regression". A command's own
// Claude Code login (the native place) is renewed by Claude Code, never by splice, so splice cannot carry its account
// across the renewal the way it does for a login it renews itself. It sees the new token when it next reads the
// credential file, which every pool pick, quota read and turn does. The goal, as the person at the console sees it:
// the first time the card is read after Claude Code renews the login, it shows the email, and a card names only the
// account its own token proves.
//
// The place is read the way the Accounts route reads it (ClaudeLoginRead.places) and observed the way the pool
// observes it (ClaudeNativeAuth). Anthropic's profile call, which proves which account a token belongs to, is stood
// in, since it cannot run here.
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.client.ClaudeHead
import splice.client.ClaudeLoginTarget
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.config.StatePaths
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

private const val HOUR_MS = 3_600_000L
private val TORAD = ClaudeAccountIdentity("uuid-torad", "marcos@torad.example")
private val SEEK = ClaudeAccountIdentity("uuid-seek", "marcos@seekapps.example")

class ClaudeNativeRenewedLoginEmailTest {
    @TempDir
    lateinit var home: Path

    private val paths by lazy { StatePaths(baseOverride = home.resolve("state")) }
    private val folder by lazy { Files.createDirectories(home.resolve(".claude")) }
    private val location by lazy {
        ClaudeLoginLocation(
            ClaudeLoginPlaceId.NATIVE,
            ClaudeLoginTarget(ClaudeHead("claude", folder), folder.resolve(".claude.json")),
            home.resolve("copies"),
        )
    }

    private fun key(token: String): String =
        requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))

    /** Claude Code writing the place's credential, live for eight hours. */
    private fun claudeCodeWrites(token: String) {
        Files.writeString(
            folder.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$token","refreshToken":"refresh-$token",""" +
                """"expiresAt":${System.currentTimeMillis() + 8 * HOUR_MS}}}""",
        )
    }

    private fun TestScope.identities(profile: ClaudeProfileCall) = ClaudeIdentityRefresh(
        backgroundScope,
        StandardTestDispatcher(testScheduler),
        ClaudeCredentialProfiles(paths.stateDir, {}),
        profile,
        {},
    )

    /** The place as the pool observes it: the auth a pool pick, quota read or turn reads the credential through. */
    private fun pool(identities: ClaudeIdentityRefresh) = ClaudeNativeAuth(
        folder,
        ClaudeLoginPlaceId.NATIVE,
        ClaudeCredentialProfiles(paths.stateDir, {}),
        {},
        identities = identities,
    )

    /** The email the place's card shows, read as the Accounts route reads it. */
    private fun card(identities: ClaudeIdentityRefresh): String? =
        ClaudeLoginRead(paths, {}, WallClock(System::currentTimeMillis), identities)
            .places(listOf(location)).single().identity.account?.email

    @Test
    fun `the first card read after Claude Code renews the login shows its email`() = runTest {
        claudeCodeWrites("native-old")
        ClaudeCredentialProfiles(paths.stateDir, {}).observed(key("native-old"), TORAD)
        val identities = identities(ClaudeProfileCall { token -> if (token == "native-renewed") TORAD else null })
        val pool = pool(identities)
        assertEquals(Credentials.Bearer("native-old"), pool.credentials())

        claudeCodeWrites("native-renewed") // Claude Code renews its own login
        assertEquals(Credentials.Bearer("native-renewed"), pool.credentials(), "the next turn reads the new token")
        runCurrent() // nobody has opened the Accounts page yet
        assertEquals(TORAD.email, card(identities), "the first card read after the renewal")
    }

    @Test
    fun `a native sign-in as another account shows no earlier email, then the account it proves`() = runTest {
        claudeCodeWrites("native-old")
        ClaudeCredentialProfiles(paths.stateDir, {}).observed(key("native-old"), TORAD)
        val answer = CompletableDeferred<Unit>()
        val identities = identities(
            ClaudeProfileCall { token ->
                if (token != "native-other-account") awaitCancellation()
                answer.await()
                SEEK
            },
        )
        val pool = pool(identities)
        pool.credentials()
        assertEquals(TORAD.email, card(identities), "before the new sign-in")

        claudeCodeWrites("native-other-account") // /login in that command, as another account
        pool.credentials()
        runCurrent()
        assertNull(card(identities), "while unproved, never the earlier account's email")

        answer.complete(Unit)
        runCurrent()
        assertEquals(SEEK.email, card(identities), "once proved, the account the new token belongs to")
    }
}
