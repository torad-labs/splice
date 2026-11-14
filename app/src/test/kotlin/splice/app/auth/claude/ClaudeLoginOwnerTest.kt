// a command's own login places: an edit stays on the place it names, and Sign in on a place's card is an added
// account's sign-in for that place's command, which writes no place.
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.signin.AccountMutation
import splice.accounts.signin.LoginFailure
import splice.accounts.signin.LoginState
import splice.client.ClaudeHead
import splice.client.ClaudeLoginTarget
import splice.client.ClaudeLogins
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.core.auth.CredentialKey
import splice.core.config.StatePaths
import splice.core.util.WallClock
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Files
import java.nio.file.Path

class ClaudeLoginOwnerTest {
    @TempDir
    lateinit var home: Path

    private fun location(): ClaudeLoginLocation {
        val folder = Files.createDirectories(home.resolve("native"))
        return ClaudeLoginLocation(
            ClaudeLoginPlaceId.NATIVE,
            ClaudeLoginTarget(ClaudeHead("claude", folder), home.resolve(".claude.json")),
            home.resolve("copies"),
        )
    }

    private fun live(location: ClaudeLoginLocation, account: String, bytes: String) {
        Files.writeString(location.credentials, bytes)
        Files.writeString(location.target.accountFile, """{"oauthAccount":{"accountUuid":"$account"}}""")
    }

    /** What the owner is wired to besides its location, scope and spawner. */
    private data class Surroundings(
        val places: List<ClaudeLoginLocation>,
        val wrap: WrapStateRead = WrapStateRead { ClaudeToRun.Wrapped("fixture-native") },
    )

    private fun owner(
        location: ClaudeLoginLocation,
        scope: CoroutineScope,
        start: NativeAuthStart,
        around: Surroundings = Surroundings(listOf(location)),
    ): ClaudeLoginOwner {
        val (places, wrap) = around
        val paths = StatePaths(baseOverride = home.resolve("state"))
        val auth = NativeClaudeAuth(wrap, emptyMap(), ProcessDispatchers().io(), start)
        val signIn = ClaudeAccountSignIn(ClaudeAccountFolders(paths.stateDir), auth, scope)
        return ClaudeLoginOwner(places, ClaudeLoginRead(paths, {}, WallClock { 1000 }), signIn)
    }

    private fun editable(location: ClaudeLoginLocation, token: String): Pair<ClaudeLoginEdits, ClaudeLogins> {
        val bytes = """{"claudeAiOauth":{"accessToken":"$token"}}"""
        Files.writeString(location.credentials, bytes)
        Files.createDirectories(location.storeDir)
        Files.writeString(location.storeDir.resolve("work.credentials.json"), bytes)
        val paths = StatePaths(baseOverride = home.resolve("state"))
        return ClaudeLoginEdits(ClaudeLoginRead(paths, {}, WallClock { 1000 })) to ClaudeLogins(location.storeDir)
    }

    // Nov 13, 2026: a Remove on this login deleted it, and every claude session on the machine said "Not logged in".
    @Test
    fun `a Remove on Claude Code's own login is refused and the login every session runs on stays`() = runBlocking {
        val native = location()
        live(native, "account-a", """{"claudeAiOauth":{"accessToken":"synthetic-native"}}""")
        val bytes = Files.readString(native.credentials)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val owner = owner(native, scope, NativeAuthStart { error("must not spawn") })

            val removed = owner.remove(ClaudeLoginPlaceId.NATIVE)

            assertTrue(removed is AccountMutation.Refused, removed.toString())
            assertTrue((removed as AccountMutation.Refused).reason.contains("/logout"), removed.reason)
            assertEquals(bytes, Files.readString(native.credentials))
            assertFalse(Files.exists(native.storeDir.resolve("removed")))
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `native rename needs credential proof and never changes the live login bytes or place id`() {
        val native = location()
        val (edits, logins) = editable(native, "synthetic-native")
        val bytes = Files.readString(native.credentials)
        assertEquals(AccountMutation.Ok, edits.relabel(native, logins, "office"))
        assertEquals(bytes, Files.readString(native.credentials))
        val key = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-native")))
        assertEquals("office", logins.labelForCredential(key))
        Files.writeString(native.credentials, """{"claudeAiOauth":{"accessToken":"synthetic-rotated"}}""")
        assertTrue(edits.relabel(native, logins, "wrong") is AccountMutation.Refused)
        assertTrue(Files.exists(native.storeDir.resolve("office.credentials.json")))
        assertFalse(Files.exists(native.storeDir.resolve("wrong.credentials.json")))
        assertEquals(ClaudeLoginPlaceId.NATIVE, native.id)
    }

    @Test
    fun `a wrap that cannot name the real claude fails the sign-in naming why, and no native process starts`() =
        runBlocking {
            val location = location()
            live(location, "outgoing", "outgoing bytes")
            val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
            val unusable = WrapStateRead {
                ClaudeToRun.Refused("claude is the splice launch shim and the wrap state /x is missing")
            }
            val owner = owner(
                location,
                scope,
                NativeAuthStart { error("must not spawn") },
                Surroundings(listOf(location), unusable),
            )
            try {
                val login = owner.login(ClaudeLoginPlaceId.NATIVE, null)
                withTimeout(5000) {
                    while (owner.poll(login.id)?.state != LoginState.FAILED) yield()
                }
                assertTrue(owner.poll(login.id)?.failureReason.orEmpty().contains("wrap state /x is missing"))
                assertEquals(LoginFailure.CONFIG, owner.poll(login.id)?.failureKind)
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }
}
