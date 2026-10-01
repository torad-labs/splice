// NEW: session liveness is positive process evidence, not recent activity or registration presence.
package splice.app.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.upstream.Ticker
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CodeModeSessionLivenessTest {
    @Test
    fun `stale processes remain live and only a positively gone process is dead`() = runTest {
        val source = ListingSource(
            SessionListing(
                listOf(
                    record("live", SessionAvailability.LIVE),
                    record("stale", SessionAvailability.STALE),
                    record("gone", SessionAvailability.GONE),
                    record("resumed", SessionAvailability.GONE),
                    record("resumed", SessionAvailability.LIVE),
                ),
            ),
        )
        val probe = CodeModeSessionLiveness(
            backgroundScope,
            source,
            Ticker { false },
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertTrue(probe("live") == true)
        assertTrue(probe("stale") == true)
        assertFalse(checkNotNull(probe("gone")))
        assertTrue(probe("resumed") == true)
        assertNull(probe("headless"))
    }

    @Test
    fun `an unavailable registry clears stale death evidence instead of declaring missing sessions dead`() = runTest {
        val next = CompletableDeferred<Unit>()
        var ticks = 0
        val source = ListingSource(SessionListing(listOf(record("session", SessionAvailability.GONE))))
        val probe = CodeModeSessionLiveness(
            backgroundScope,
            source,
            Ticker {
                if (ticks++ == 0) {
                    next.await()
                    true
                } else {
                    false
                }
            },
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertEquals(false, probe("session"))
        source.listing = SessionListing(emptyList(), "fixture unavailable")
        next.complete(Unit)
        runCurrent()
        assertNull(probe("session"))
    }

    @Test
    fun `a failed refresh is logged and the next refresh still discovers the live process`() = runTest {
        var reads = 0
        val logs = mutableListOf<String>()
        val source = object : SessionSource {
            override fun read(): List<SessionRecord> = list().sessions
            override fun list(): SessionListing {
                if (reads++ == 0) throw IOException("fixture read failed")
                return SessionListing(listOf(record("session", SessionAvailability.LIVE)))
            }
        }
        var ticks = 0
        val probe = CodeModeSessionLiveness(
            backgroundScope,
            source,
            Ticker { ticks++ == 0 },
            StandardTestDispatcher(testScheduler),
            log = { logs += it },
        )
        runCurrent()
        assertEquals(true, probe("session"))
        assertEquals(1, logs.size)
    }

    @Test
    fun `a registration without a valid process id provides no death evidence`() = runTest {
        val source = ListingSource(
            SessionListing(
                listOf(
                    record("missing", SessionAvailability.GONE).copy(pid = null),
                    record("zero", SessionAvailability.GONE).copy(pid = 0),
                    record("negative", SessionAvailability.GONE).copy(pid = -1),
                ),
            ),
        )
        val probe = CodeModeSessionLiveness(
            backgroundScope,
            source,
            Ticker { false },
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertNull(probe("missing"))
        assertNull(probe("zero"))
        assertNull(probe("negative"))
    }

    private fun record(id: String, availability: SessionAvailability): SessionRecord = SessionRecord(
        pid = 123,
        sessionId = id,
        cwd = null,
        name = null,
        kind = null,
        version = null,
        status = null,
        statusUpdatedAt = null,
        startedAt = null,
        updatedAt = null,
        messagingSocketPath = null,
        route = SessionRoute.Unknown,
        availability = availability,
    )

    private class ListingSource(var listing: SessionListing) : SessionSource {
        override fun read(): List<SessionRecord> = listing.sessions
        override fun list(): SessionListing = listing
    }
}
