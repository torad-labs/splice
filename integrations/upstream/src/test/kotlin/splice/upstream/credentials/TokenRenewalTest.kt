// NEW: a lookup that read a credential before a renewal landed is answered with what that renewal granted. It never
// starts a second renewal: a refresh token is single-use, so the second one is a needless rotation at best and, when
// the endpoint answers it badly, a login latched dead at worst.
package splice.upstream.credentials

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.Credentials
import splice.core.auth.LoginAnswer
import splice.core.auth.RefreshOutcome
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

private const val NOW_MS = 1_800_000_000_000L
private const val MINUTE_MS = 60_000L

class TokenRenewalTest {
    @TempDir
    lateinit var folder: Path

    @Test
    fun `a lookup that read the token before a renewal landed gets the renewed token, not a second renewal`() =
        runTest {
            val authPath = folder.resolve("auth.json")
            Files.writeString(authPath, "access-old")
            val spent = mutableListOf<String>()
            // A rotating vendor: the file holds the access token; a rotation grants the next one, and the endpoint
            // remembers which refresh tokens it was asked to spend.
            val renewal = TokenRenewal(
                authPath,
                "test",
                WallClock { NOW_MS },
                LogSink { },
                null,
                RenewalStage { prior ->
                    val onFile = Files.readString(authPath)
                    if (prior != null && onFile != prior) {
                        RefreshOutcome.Refreshed(Credentials.Bearer(onFile))
                    } else {
                        spent += "refresh-of-$onFile"
                        val granted = "access-${spent.size}"
                        Files.writeString(authPath, granted)
                        RefreshOutcome.Refreshed(Credentials.Bearer(granted))
                    }
                },
            )
            val windows = RenewalWindows(proactiveMs = 5 * MINUTE_MS, floorMs = 5 * MINUTE_MS)
            val readBeforeRenewal = HeldToken(Credentials.Bearer("access-old"), NOW_MS + MINUTE_MS)

            val first = renewal.answer(readBeforeRenewal, windows)
            // The concurrent turn: it read the file before the first renewal wrote it, and asks after that renewal ended.
            val late = renewal.answer(readBeforeRenewal, windows)

            assertEquals(LoginAnswer.Granted(Credentials.Bearer("access-1")), first)
            assertEquals(first, late, "both turns send the one renewed token")
            assertEquals(listOf("refresh-of-access-old"), spent, "only the old refresh token was ever spent")
        }
}
