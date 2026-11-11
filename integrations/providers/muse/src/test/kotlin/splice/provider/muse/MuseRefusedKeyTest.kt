// NEW: a turn refused for a key that a mint already replaced sends the minted key; it never mints again, which could
// revoke the key every other turn now sends (Nov 10, 2026).
package splice.provider.muse

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.Credentials
import splice.core.auth.RefreshOutcome
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class MuseRefusedKeyTest {

    @Test
    fun `two turns refused for the same key mint once, and both send the minted key`(@TempDir tempDir: Path) =
        runTest {
            val minted = AtomicInteger()
            val auth = provider(authFile(tempDir, apiKey = "old-key")) { _, _ ->
                MuseMintAttempt.Granted(usageKey("minted-${minted.incrementAndGet()}"))
            }
            val refused = Credentials.Bearer("old-key")

            val first = auth.exchange(refused)
            // The second turn sent the old key too, and is refused after the first turn's mint landed.
            val second = auth.exchange(refused)

            assertEquals(RefreshOutcome.Refreshed(Credentials.Bearer("minted-1")), first)
            assertEquals(first, second, "the second turn sends the key the first mint landed")
        }

    @Test
    fun `a turn refused for the key on file mints a new one`(@TempDir tempDir: Path) = runTest {
        val auth = provider(authFile(tempDir, apiKey = "dead-key")) { _, _ ->
            MuseMintAttempt.Granted(usageKey("fresh-key"))
        }

        val renewed = auth.exchange(Credentials.Bearer("dead-key"))

        assertEquals(RefreshOutcome.Refreshed(Credentials.Bearer("fresh-key")), renewed)
    }
}
