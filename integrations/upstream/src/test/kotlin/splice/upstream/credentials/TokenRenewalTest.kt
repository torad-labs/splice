// NEW: a lookup that read a credential before a renewal landed, or a request refused for a token a renewal already
// replaced, is answered with what that renewal granted. It never starts a second renewal: a refresh token is
// single-use, so the second one is a needless rotation at best and, when the endpoint answers it badly, a login latched
// dead at worst. A refusal latches only the file state whose token was refused.
package splice.upstream.credentials

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.Credentials
import splice.core.auth.INVALID_GRANT_REASON
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

    // Nov 10, 2026: the 401 path asked about the token this process served last, which the first 401's renewal had
    // just made the new one, so a second 401 for the old token rotated the new one too.
    @Test
    fun `two 401s for the same token rotate it once, however far apart they arrive`() = runTest {
        val authPath = folder.resolve("auth.json")
        Files.writeString(authPath, "access-old")
        val endpoint = Endpoint(authPath)
        val renewal = endpoint.renewal()
        val refused = Credentials.Bearer("access-old")

        val together = listOf(async { renewal.exchange(refused) }, async { renewal.exchange(refused) }).awaitAll()
        // A third turn that also sent the old token is refused after both renewals above ended.
        val late = renewal.exchange(refused)

        val renewed = RefreshOutcome.Refreshed(Credentials.Bearer("access-1"))
        assertEquals(listOf(renewed, renewed, renewed), together + late, "every refused turn resends the one renewal")
        assertEquals(listOf("refresh-of-access-old"), endpoint.spent, "the old refresh token is spent once")
        assertEquals("access-1", Files.readString(authPath))
    }

    @Test
    fun `a 401 for a token a renewal already replaced adopts the pair on file and posts nothing`() = runTest {
        val authPath = folder.resolve("auth.json")
        // Another turn's renewal (or another splice) already rotated the token this turn sent.
        Files.writeString(authPath, "access-new")
        val endpoint = Endpoint(authPath)

        val outcome = endpoint.renewal().exchange(Credentials.Bearer("access-old"))

        assertEquals(RefreshOutcome.Refreshed(Credentials.Bearer("access-new")), outcome)
        assertEquals(emptyList<String>(), endpoint.spent, "no refresh token was posted")
    }

    // The latch is armed against the file state whose refresh token was refused, so a second 401 for a token already
    // replaced, which an endpoint that refuses a token reused or rotated too soon would answer invalid_grant, never
    // gets to post and latch the good pair on file.
    @Test
    fun `a rejection for a stale token does not latch the pair now on file`() = runTest {
        val authPath = folder.resolve("auth.json")
        Files.writeString(authPath, "access-old")
        // An endpoint that grants one rotation and refuses every later one.
        val endpoint = Endpoint(authPath, grants = 1)
        val renewal = endpoint.renewal()
        val refused = Credentials.Bearer("access-old")

        renewal.exchange(refused)
        val stale = renewal.exchange(refused)

        assertEquals(RefreshOutcome.Refreshed(Credentials.Bearer("access-1")), stale)
        assertFalse(renewal.latch.isLatched(renewal.identity()), "the renewed pair on file is not latched")
    }

    @Test
    fun `a rejection while the file moves under it latches nothing, and the moved file is renewed`() = runTest {
        val authPath = folder.resolve("auth.json")
        Files.writeString(authPath, "access-old")
        var peerWrites = true
        val spent = mutableListOf<String>()
        val renewal = TokenRenewal(
            authPath,
            "test",
            WallClock { NOW_MS },
            LogSink { },
            null,
            RenewalStage { _ ->
                val onFile = Files.readString(authPath)
                if (peerWrites) {
                    // A peer that does not take splice's lock rotated the token while this POST was in flight, so the
                    // endpoint refused a token the file no longer holds.
                    peerWrites = false
                    Files.writeString(authPath, "access-peer")
                    RefreshOutcome.Rejected(INVALID_GRANT_REASON)
                } else {
                    spent += onFile
                    Files.writeString(authPath, "access-renewed")
                    RefreshOutcome.Refreshed(Credentials.Bearer("access-renewed"))
                }
            },
        )

        val refusedForOld = renewal.exchange(Credentials.Bearer("access-old"))
        val next = renewal.exchange(Credentials.Bearer("access-peer"))

        assertEquals(RefreshOutcome.Rejected(INVALID_GRANT_REASON), refusedForOld)
        assertEquals(RefreshOutcome.Refreshed(Credentials.Bearer("access-renewed")), next)
        assertEquals(listOf("access-peer"), spent, "the peer's pair was not latched by a refusal of the old one")
    }

    @Test
    fun `a rejection of the token on file latches it until the file changes`() = runTest {
        val authPath = folder.resolve("auth.json")
        Files.writeString(authPath, "access-dead")
        val endpoint = Endpoint(authPath, grants = 0)
        val renewal = endpoint.renewal()

        val refused = renewal.exchange(Credentials.Bearer("access-dead"))

        assertEquals(RefreshOutcome.Rejected(INVALID_GRANT_REASON), refused)
        assertTrue(renewal.latch.isLatched(renewal.identity()), "the refused state is latched")
        Files.writeString(authPath, "access-relogin")
        assertFalse(renewal.latch.isLatched(renewal.identity()), "a re-login lifts it")
    }
}

/** A rotating vendor over [authPath], which holds the access token: a renewal asked about a token the file no longer
 *  holds adopts the file's; otherwise it posts, and the endpoint grants the next token for the first [grants] posts and
 *  answers invalid_grant after. [spent] is what the endpoint was asked to spend. */
private class Endpoint(private val authPath: Path, private val grants: Int = Int.MAX_VALUE) {
    val spent = mutableListOf<String>()

    fun renewal(): TokenRenewal = TokenRenewal(
        authPath,
        "test",
        WallClock { NOW_MS },
        LogSink { },
        null,
        RenewalStage { prior ->
            val onFile = Files.readString(authPath)
            when {
                prior != null && onFile != prior -> RefreshOutcome.Refreshed(Credentials.Bearer(onFile))
                spent.size >= grants -> RefreshOutcome.Rejected(INVALID_GRANT_REASON).also { spent += onFile }
                else -> {
                    spent += "refresh-of-$onFile"
                    val granted = "access-${spent.size}"
                    Files.writeString(authPath, granted)
                    RefreshOutcome.Refreshed(Credentials.Bearer(granted))
                }
            }
        },
    )
}
