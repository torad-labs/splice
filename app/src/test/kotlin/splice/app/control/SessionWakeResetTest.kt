package splice.app.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.app.control.wake.SessionWakePolicy
import splice.core.perf.OutcomeTag
import splice.sessions.http.SessionEnding
import splice.sessions.http.SessionEndingOf
import splice.sessions.note.NoteOutcome
import splice.sessions.note.SessionNoteSender
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.SessionStatus

@OptIn(ExperimentalCoroutinesApi::class)
class SessionWakeResetTest {
    private fun session(id: String, state: String = "idle") = SessionRecord(
        sessionId = id,
        name = null,
        status = SessionStatus(state),
        route = SessionRoute.Head("test"),
        availability = SessionAvailability.LIVE,
        process = SessionProcess(pid = 1, cwd = null, startedAt = null, updatedAt = 1, messagingSocketPath = "/s/$id"),
        client = SessionClient(kind = null, version = "test"),
    )

    private fun source(records: List<SessionRecord>) = object : SessionSource {
        override fun read() = records
        override fun list() = SessionListing(records)
    }

    @Test
    fun `a restarted held session receives one note at its recorded reset`() = runTest {
        val sent = mutableListOf<String>()
        val ending = SessionEnding(OutcomeTag.RESTARTED.wire, "one", 11_000, 1_000, "test")
        val wake = SessionWake(
            source(listOf(session("waiting"))),
            SessionEndingOf { ending },
            SessionNoteSender { _, text -> sent += text; NoteOutcome.Submitted },
            backgroundScope,
            {},
            SessionWakePolicy(SessionTurnEnded { _, _ -> true }, clock = { 2_000 + testScheduler.currentTime }),
        )
        wake.start()
        runCurrent()
        advanceTimeBy(8_999)
        runCurrent()
        assertEquals(emptyList<String>(), sent)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("[splice] test's stated reset has passed. Continue where you left off."), sent)
        wake.joined("test")
        runCurrent()
        assertEquals(1, sent.size)
        wake.stop()
    }

    @Test
    fun `a reset beyond seven days still wakes an untouched session and never one its person resumed`() = runTest {
        val sent = mutableListOf<String?>()
        val reset = 8L * 24 * 60 * 60 * 1_000
        val ending = SessionEnding(OutcomeTag.PLAN_LIMIT.wire, "one", reset, 1_000, "test")
        val wake = SessionWake(
            source(listOf(session("untouched"), session("touched"), session("stopped"))),
            SessionEndingOf { ending },
            SessionNoteSender { target, _ -> sent += target.sessionId; NoteOutcome.Submitted },
            backgroundScope,
            {},
            SessionWakePolicy(
                SessionTurnEnded { record, _ -> record.sessionId != "touched" },
                clock = { 2_000 + testScheduler.currentTime },
            ),
        )
        wake.acted("stopped")
        wake.start()
        runCurrent()
        advanceTimeBy(reset - 2_000)
        runCurrent()
        assertEquals(listOf("untouched"), sent)
        wake.stop()
    }

    @Test
    fun `a new login wakes a single-login client's own retry and leaves a pooled retry to itself`() = runTest {
        val sent = mutableListOf<String>()
        for (pooled in listOf(false, true)) {
            val now = 2_000 + testScheduler.currentTime
            val ending = SessionEnding(OutcomeTag.PLAN_LIMIT.wire, "one", now + 9_000, now - 1, "test")
            val wake = SessionWake(
                source(listOf(session("waiting", "busy"))),
                SessionEndingOf { ending },
                SessionNoteSender { _, text -> sent += text; NoteOutcome.Submitted },
                backgroundScope,
                {},
                SessionWakePolicy(
                    SessionTurnEnded { _, _ -> true },
                    clock = { 2_000 + testScheduler.currentTime },
                    pooled = SessionHeadPooled { pooled },
                ),
            )
            wake.start()
            runCurrent()
            advanceTimeBy(9_000)
            runCurrent()
            wake.joined("test")
            runCurrent()
            wake.stop()
        }
        assertEquals(listOf("[splice] test has a new login. Continue where you left off."), sent)
    }

    @Test
    fun `a late old event cannot replace a newer reset`() = runTest {
        val id = "waiting-session"
        val sent = mutableListOf<String>()
        val old = SessionEnding(OutcomeTag.PLAN_LIMIT.wire, "one", 5_000, 1_000, "test")
        var ending: SessionEnding? = null
        var changed: SessionWakeEvent? = null
        val wake = SessionWake(
            source(listOf(session(id))),
            SessionEndingOf { ending },
            SessionNoteSender { _, text -> sent += text; NoteOutcome.Submitted },
            backgroundScope,
            {},
            SessionWakePolicy(SessionTurnEnded { _, _ -> true }, clock = { 2_000 + testScheduler.currentTime }),
            observers = SessionWakeObservers { listener -> changed = listener; emptyList() },
        )
        wake.start()
        runCurrent()
        val newer = old.copy(resetMs = 10_000, atMs = 2_000)
        ending = newer
        requireNotNull(changed).ended(id, newer)
        requireNotNull(changed).ended(id, old)
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(listOf("[splice] test's stated reset has passed. Continue where you left off."), sent)
        wake.stop()
    }

    @Test
    fun `a newer clean turn cancels the wake restored at daemon start`() = runTest {
        val sent = mutableListOf<String>()
        var ending: SessionEnding? = SessionEnding(OutcomeTag.PLAN_LIMIT.wire, "one", 11_000, 1_000, "test")
        val wake = SessionWake(
            source(listOf(session("waiting"))),
            SessionEndingOf { ending },
            SessionNoteSender { _, text -> sent += text; NoteOutcome.Submitted },
            backgroundScope,
            {},
            SessionWakePolicy(SessionTurnEnded { _, _ -> true }, clock = { 2_000 + testScheduler.currentTime }),
        )
        wake.start()
        runCurrent()
        ending = null
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(emptyList<String>(), sent)
        wake.stop()
    }
}
