// NEW: the Claude half of failover within one provider. An account splice added lives in a folder splice owns, so
// splice is its ONLY user and its only refresher: the token comes from the folder, a token near its expiry is
// refreshed once however many turns ask at the same moment, and the rotated pair is saved back owner-only in Claude
// Code's own file format. The caller's own sign-in is the one login splice never refreshes.
package splice.app.auth.claude

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.Credentials
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicInteger

private const val NOW_MS = 1_800_000_000_000L
private const val MINUTE_MS = 60_000L
private val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

class ClaudeFolderAuthTest {
    @TempDir
    lateinit var folder: Path

    private val calls = AtomicInteger()

    private fun write(accessToken: String, refreshToken: String, expiresAt: Long) {
        Files.writeString(
            folder.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$accessToken","refreshToken":"$refreshToken",""" +
                """"expiresAt":$expiresAt,"scopes":["user:inference","user:profile"],"subscriptionType":"max"}}""",
        )
    }

    private fun auth(rotate: Boolean = true): ClaudeFolderAuth = ClaudeFolderAuth(
        folder,
        clock = WallClock { NOW_MS },
        refresh = ClaudeTokenRefresh {
            calls.incrementAndGet()
            if (rotate) {
                ClaudeRefreshedTokens("access-${calls.get()}", "refresh-${calls.get()}", NOW_MS + 60 * MINUTE_MS)
            } else {
                null // What the endpoint refusing a spent refresh token looks like here.
            }
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
        val saved = Files.readString(folder.resolve(".credentials.json"))
        assertTrue(saved.contains(""""accessToken":"access-1""""), "the rotated access token is saved back")
        assertTrue(saved.contains(""""refreshToken":"refresh-1""""), "the rotated refresh token too, single-use")
        assertTrue(saved.contains(""""subscriptionType":"max""""), "every other field Claude Code wrote is kept")
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(folder.resolve(".credentials.json")))
    }

    @Test
    fun `a refused refresh keeps the folder as it was and sends nothing`() = runTest {
        write("access-old", "refresh-old", expiresAt = NOW_MS - MINUTE_MS)
        val before = Files.readString(folder.resolve(".credentials.json"))

        assertNull(auth(rotate = false).credentials(), "an expired token splice cannot rotate is no credential")

        assertEquals(before, Files.readString(folder.resolve(".credentials.json")), "untouched")
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
