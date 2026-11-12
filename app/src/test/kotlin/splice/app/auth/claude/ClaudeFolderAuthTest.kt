// the Claude half of failover within one provider. An account splice added lives in a folder splice owns, so
// splice is its ONLY user and its only refresher: the token comes from the folder, a token near its expiry is
// refreshed once however many turns ask at the same moment, and the rotated pair is saved back owner-only in Claude
// Code's own file format. The caller's own sign-in is the one login splice never refreshes.
//
// Audit, Nov 9, 2026: a token still alive is served when its rotation fails, a rotated pair whose write fails is kept
// and served, and a 401 rotates even a token outside the proactive window.
package splice.app.auth.claude

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger

private const val NOW_MS = 1_800_000_000_000L
private const val MINUTE_MS = 60_000L
private val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

class ClaudeFolderAuthTest {
    @TempDir
    lateinit var folder: Path

    private val calls = AtomicInteger()

    private val credentialsFile: Path get() = folder.resolve(".credentials.json")

    private fun write(accessToken: String, refreshToken: String, expiresAt: Long) {
        Files.writeString(
            credentialsFile,
            """{"claudeAiOauth":{"accessToken":"$accessToken","refreshToken":"$refreshToken",""" +
                """"expiresAt":$expiresAt,"scopes":["user:inference","user:profile"],"subscriptionType":"max"}}""",
        )
    }

    /** [answer] is what the token endpoint says to each rotation; the default grants a fresh pair. */
    private fun auth(
        answer: () -> RefreshAttempt<ClaudeRefreshedTokens> = {
            RefreshAttempt.Granted(
                ClaudeRefreshedTokens("access-${calls.get()}", "refresh-${calls.get()}", NOW_MS + 60 * MINUTE_MS),
            )
        },
    ): ClaudeFolderAuth = ClaudeFolderAuth(
        folder,
        clock = WallClock { NOW_MS },
        refresh = ClaudeTokenRefresh {
            calls.incrementAndGet()
            answer()
        },
    )

    @Test
    fun `a live token is sent from the folder, with no refresh`() = runTest {
        write("access-live", "refresh-live", expiresAt = NOW_MS + 60 * MINUTE_MS)

        assertEquals(Credentials.Bearer("access-live"), auth().credentials())
        assertEquals(0, calls.get(), "a live token is never refreshed")
    }

    @Test
    fun `a token inside the proactive window is refreshed and saved back, once for concurrent turns`() = runTest {
        write("access-old", "refresh-old", expiresAt = NOW_MS + MINUTE_MS)
        val auth = auth()

        val both = listOf(async { auth.credentials() }, async { auth.credentials() }).map { it.await() }

        assertEquals(listOf(Credentials.Bearer("access-1"), Credentials.Bearer("access-1")), both)
        assertEquals(1, calls.get(), "one refresh however many turns asked")
        val saved = Files.readString(credentialsFile)
        assertTrue(saved.contains(""""accessToken":"access-1""""), "the rotated access token is saved back")
        assertTrue(saved.contains(""""refreshToken":"refresh-1""""), "the rotated refresh token too, single-use")
        assertTrue(saved.contains(""""subscriptionType":"max""""), "every other field Claude Code wrote is kept")
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(credentialsFile))
    }

    @Test
    fun `a token still alive is sent when its rotation is refused`() = runTest {
        write("access-old", "refresh-old", expiresAt = NOW_MS + MINUTE_MS)

        val sent = auth { RefreshAttempt.Unavailable("token refresh refused with 503") }.credentials()

        assertEquals(Credentials.Bearer("access-old"), sent, "a minute of life left is a credential, not none")
    }

    @Test
    fun `an expired token whose rotation is refused keeps the folder as it was and sends nothing`() = runTest {
        write("access-old", "refresh-old", expiresAt = NOW_MS - MINUTE_MS)
        val before = Files.readString(credentialsFile)

        assertNull(
            auth { RefreshAttempt.InvalidGrant("token refresh refused with 400") }.credentials(),
            "an expired token splice cannot rotate is no credential",
        )

        assertEquals(before, Files.readString(credentialsFile), "untouched")
    }

    @Test
    fun `a 401 rotates a token outside the window instead of answering with the same token`() = runTest {
        write("access-live", "refresh-live", expiresAt = NOW_MS + 60 * MINUTE_MS)
        val auth = auth()

        assertEquals(Credentials.Bearer("access-live"), auth.credentials())
        assertEquals(Credentials.Bearer("access-1"), auth.refresh(), "the token that drew the 401 is never resent")
        assertEquals(1, calls.get())
    }

    @Test
    fun `a rotated pair whose write fails is kept, sent, and written once the folder takes it`() = runTest {
        write("access-old", "refresh-old", expiresAt = NOW_MS + MINUTE_MS)
        Files.writeString(folder.resolve(".credentials.json.lock"), "")
        val writable = Files.getPosixFilePermissions(folder)
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"))
        val auth = auth()
        try {
            assertEquals(Credentials.Bearer("access-1"), auth.credentials())
            assertEquals(Credentials.Bearer("access-1"), auth.credentials(), "the kept pair, not a second rotation")
            assertEquals(1, calls.get(), "Anthropic already spent the old refresh token; it is never posted again")
        } finally {
            Files.setPosixFilePermissions(folder, writable)
        }

        assertEquals(Credentials.Bearer("access-1"), auth.credentials())
        val saved = Files.readString(credentialsFile)
        assertTrue(saved.contains(""""refreshToken":"refresh-1""""), "the kept pair lands once the folder is writable")
    }

    @Test
    fun `a folder with no credential is no credential, and refresh does not invent one`() = runTest {
        val auth = auth()

        assertNull(auth.credentials())
        assertNull(auth.refresh())
        assertEquals(0, calls.get())
    }

    @Test
    fun `the description says the account's own folder and never a token`() = runTest {
        write("access-live", "refresh-live", expiresAt = NOW_MS + 60 * MINUTE_MS)

        val described = auth().describe()

        assertTrue(described.present)
        assertEquals("claude-account", described.kind)
        assertTrue(described.fields.values.none { it.contains("access-live") })
    }
}
