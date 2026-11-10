// NEW: audit follow-ups, Nov 9, 2026. The usage poll's own backoff (a mint that keeps refusing the poll is asked less
// often, and an inactive subscription it finds is shown without holding a turn), and a minted key whose write failed,
// kept and served until the folder takes it, as codex, grok and kimi keep a renewed pair. The fixtures are the
// top-level ones in MuseAuthProviderFixesTest.kt.
package splice.provider.muse

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.Credentials
import splice.core.auth.LoginAnswer
import splice.core.auth.RefreshOutcome
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger

private const val TEN_MINUTES_MS = 600_000L

class MusePollBackoffTest {

    @Test
    fun `a refused poll waits longer each time before it asks the mint again, and never holds a turn`(
        @TempDir tempDir: Path,
    ) = runTest {
        var nowMs = 0L
        val posts = AtomicInteger()
        val auth = provider(authFile(tempDir), clock = WallClock { nowMs }) { _, _ ->
            posts.incrementAndGet()
            MuseMintAttempt.Unavailable("key mint unavailable (HTTP 503)")
        }

        assertNull(auth.usageFields())
        assertEquals(1, posts.get())
        nowMs = 1L
        assertNull(auth.usageFields())
        assertEquals(1, posts.get(), "the poll waits a minute after its first refusal")

        assertEquals(LoginAnswer.Granted(Credentials.Bearer("persisted-key")), auth.answer())
        assertTrue(auth.exchange() is RefreshOutcome.Unavailable)
        assertEquals(2, posts.get(), "a turn's own renewal still reaches the mint while the poll waits")

        nowMs = DEFAULT_RATE_HOLD_MS
        assertNull(auth.usageFields())
        assertEquals(3, posts.get(), "the minute is up")
        nowMs = DEFAULT_RATE_HOLD_MS * 3 - 1
        assertNull(auth.usageFields())
        assertEquals(3, posts.get(), "the second refusal in a row doubles the wait")
        nowMs = DEFAULT_RATE_HOLD_MS * 3
        assertNull(auth.usageFields())
        assertEquals(4, posts.get())
    }

    @Test
    fun `a poll waits at least as long as the mint's Retry-After`(@TempDir tempDir: Path) = runTest {
        var nowMs = 0L
        val posts = AtomicInteger()
        val auth = provider(authFile(tempDir), clock = WallClock { nowMs }) { _, _ ->
            posts.incrementAndGet()
            MuseMintAttempt.RateLimited(TEN_MINUTES_MS)
        }
        assertNull(auth.usageFields())
        nowMs = TEN_MINUTES_MS - 1
        assertNull(auth.usageFields())
        assertEquals(1, posts.get())
        nowMs = TEN_MINUTES_MS
        assertNull(auth.usageFields())
        assertEquals(2, posts.get())
    }

    @Test
    fun `an inactive subscription a poll finds is shown until a mint grants a key, and refuses no turn`(
        @TempDir tempDir: Path,
    ) = runTest {
        var nowMs = 0L
        val active = AtomicInteger()
        val file = authFile(tempDir)
        val before = Files.readString(file)
        val auth = provider(file, clock = WallClock { nowMs }) { _, _ ->
            if (active.get() == 0) {
                MuseMintAttempt.SubscriptionRequired("https://www.meta.ai/settings?plan=1")
            } else {
                MuseMintAttempt.Granted(usageKey("must-not-be-written"))
            }
        }

        assertNull(auth.usageFields())
        val described = auth.describe().fields
        assertEquals("inactive", described["subscription"])
        assertEquals("https://www.meta.ai/", described["action_url"])
        assertEquals("persisted-key", (auth.credentials() as Credentials.Bearer).token)
        assertEquals(LoginAnswer.Granted(Credentials.Bearer("persisted-key")), auth.answer())
        assertEquals(before, Files.readString(file), "the poll persists nothing")

        active.set(1)
        nowMs = DEFAULT_RATE_HOLD_MS
        assertTrue(auth.usageFields() != null)
        assertNull(auth.describe().fields["subscription"], "a granted mint clears what the poll found")
    }
}

class MuseKeptKeyTest {

    // Audit, Nov 9, 2026: a turn mints because its stored key stopped working, and a minted key whose write failed was
    // dropped, so the dead key stayed on disk and every turn after it minted again.
    @Test
    fun `a minted key whose write fails is served, and written once the folder takes it`(
        @TempDir tempDir: Path,
    ) = runTest {
        val file = authFile(tempDir)
        val auth = provider(file) { _, _ -> MuseMintAttempt.Granted(usageKey("minted-key")) }
        // The CredentialLock sibling exists first, so its own open needs no write access to the folder.
        Files.createFile(file.resolveSibling("${file.fileName}.lock"))
        val writable = Files.getPosixFilePermissions(file.parent)
        Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assertEquals("minted-key", (auth.refresh() as Credentials.Bearer).token)
            assertEquals("minted-key", (auth.credentials() as Credentials.Bearer).token, "the kept key serves")
            assertFalse("minted-key" in Files.readString(file), "the folder still refuses the write")
        } finally {
            Files.setPosixFilePermissions(file.parent, writable)
        }
        assertEquals("minted-key", (auth.credentials() as Credentials.Bearer).token)
        assertTrue("minted-key" in Files.readString(file), "the kept key is written at the next lookup")
    }
}
