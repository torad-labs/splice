// NEW: V4-319 — one head's live turns and the operator's stop, on real gate slots and a stepped clock.
//
// What is pinned: a turn is listed from admission until its slot is released and no longer; a stop
// cancels exactly the turn's own job with an OperatorStop, including a stop that lands before the
// drive has a job; and the stop's mark refuses the session's stream=false re-send of the same
// messages ONCE, inside the window, and nothing else. HeadServerTurnStopTest proves the same on the
// wire through a real head.
package splice.head.turn

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.ElapsedClock
import splice.head.admission.admittedSlot
import splice.head.wire.TurnIdMint
import splice.upstream.retry.InflightGate

class LiveTurnsTest {

    private var now = 1_000L
    private var minted = 0
    private val turns = LiveTurns(clock = ElapsedClock { now }, ids = TurnIdMint { "turn-${++minted}" })
    private val gate = InflightGate({ 4 })

    private fun slot(): InflightGate.Slot = runBlocking { gate.admittedSlot() }

    private fun meta(session: String?, model: String = "gpt-5.6-sol", compact: Boolean = false) = TurnMeta(
        compact = compact,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = "claude-codex--$model",
        upstreamModel = model,
        clientMaxTokens = 8000,
        effort = "high",
        summary = "detailed",
        budgetTokens = null,
        sessionId = session,
    )

    private fun request(text: String, stream: Boolean): JsonObject = Json.parseToJsonElement(
        """{"model":"claude-codex--gpt-5.6-sol","stream":$stream,
            "messages":[{"role":"user","content":"$text"}]}""",
    ).jsonObject

    private fun hash(text: String): String? = MessagesHash.of(request(text, stream = true))

    /** What [job] completed with, read through the public completion handler: a cancelled plain Job
     *  completes at once, and a handler on a completed job runs immediately. Null while it runs. */
    private fun causeOf(job: Job): Throwable? {
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }
        return cause
    }

    @Test
    fun `a turn is listed from admission until its slot is released, oldest first, with its age`() {
        val first = slot()
        turns.admitted(first, meta("sess-a", compact = true), hash("go"))
        now += 50
        val second = slot()
        turns.admitted(second, meta(null, model = "gpt-5.6-terra"), null)
        now += 25

        assertEquals(
            listOf(
                LiveTurn("turn-1", "sess-a", "gpt-5.6-sol", compact = true, ageMs = 75, stopped = false),
                LiveTurn("turn-2", null, "gpt-5.6-terra", compact = false, ageMs = 25, stopped = false),
            ),
            turns.list(),
        )

        first.release()
        assertEquals(listOf("turn-2"), turns.list().map { it.id })
        second.release()
        assertEquals(emptyList<LiveTurn>(), turns.list())
    }

    @Test
    fun `a stop cancels the turn's own job with an OperatorStop and answers its session`() {
        val slot = slot()
        turns.admitted(slot, meta("sess-a"), hash("go"))
        val job = Job()
        val other = Job()
        turns.driving(slot, job)

        assertEquals(LiveTurns.Stopped("sess-a"), turns.stop("turn-1"))

        assertTrue(job.isCancelled, "the stopped turn's job is cancelled")
        assertTrue(causeOf(job) is OperatorStop, "${causeOf(job)}")
        assertFalse(other.isCancelled, "no other job is touched")
        assertEquals(listOf(true), turns.list().map { it.stopped }, "listed as stopped until the slot goes")
        slot.release()
        assertNull(turns.stop("turn-1"), "a released turn is no longer live")
    }

    @Test
    fun `a stop that lands before the drive has a job cancels the job when it arrives`() {
        val slot = slot()
        turns.admitted(slot, meta("sess-a"), hash("go"))
        turns.stop("turn-1")
        val job = Job()

        turns.driving(slot, job)

        assertTrue(causeOf(job) is OperatorStop, "${causeOf(job)}")
    }

    @Test
    fun `a job for a slot that was never admitted is left alone`() {
        val job = Job()
        turns.driving(slot(), job)
        assertFalse(job.isCancelled)
        assertNull(turns.stop("turn-1"), "an id nothing minted is not live")
    }

    @Test
    fun `the stop's mark refuses that session's re-send of the same messages once, inside the window`() {
        val slot = slot()
        turns.admitted(slot, meta("sess-a"), hash("go"))
        turns.stop("turn-1")
        now += STOP_RESEND_WINDOW_MS

        assertFalse(turns.refusesResend("sess-b", request("go", stream = false)), "another session")
        assertFalse(turns.refusesResend(null, request("go", stream = false)), "no session")
        assertFalse(turns.refusesResend("sess-a", request("other", stream = false)), "other messages")
        assertTrue(turns.refusesResend("sess-a", request("go", stream = false)), "the re-send, at the window's edge")
        assertFalse(turns.refusesResend("sess-a", request("go", stream = false)), "the mark is used once")
    }

    @Test
    fun `a re-send after the window goes through, and the stale mark is spent`() {
        turns.admitted(slot(), meta("sess-a"), hash("go"))
        turns.stop("turn-1")
        now += STOP_RESEND_WINDOW_MS + 1

        assertFalse(turns.refusesResend("sess-a", request("go", stream = false)))
    }

    @Test
    fun `a second stop of the same turn answers again and leaves no second mark`() {
        turns.admitted(slot(), meta("sess-a"), hash("go"))
        turns.stop("turn-1")
        assertTrue(turns.refusesResend("sess-a", request("go", stream = false)))

        assertEquals(LiveTurns.Stopped("sess-a"), turns.stop("turn-1"))

        assertFalse(turns.refusesResend("sess-a", request("go", stream = false)))
    }

    @Test
    fun `a turn with no session is stopped and marks nothing`() {
        val slot = slot()
        turns.admitted(slot, meta(null), null)
        val job = Job()
        turns.driving(slot, job)

        assertEquals(LiveTurns.Stopped(null), turns.stop("turn-1"))

        assertTrue(job.isCancelled)
        assertFalse(turns.refusesResend(null, request("go", stream = false)))
    }

    @Test
    fun `a new stop prunes marks already past the window`() {
        turns.admitted(slot(), meta("sess-a"), hash("go"))
        turns.stop("turn-1")
        now += STOP_RESEND_WINDOW_MS + 1
        turns.admitted(slot(), meta("sess-b"), hash("go"))
        turns.stop("turn-2")
        now -= STOP_RESEND_WINDOW_MS + 1

        assertFalse(turns.refusesResend("sess-a", request("go", stream = false)), "pruned by the later stop")
        assertTrue(turns.refusesResend("sess-b", request("go", stream = false)))
    }

    @Test
    fun `the messages hash reads the messages alone, not stream or anything else in the body`() {
        assertEquals(MessagesHash.of(request("go", stream = true)), MessagesHash.of(request("go", stream = false)))
        assertNotEquals(MessagesHash.of(request("go", stream = true)), MessagesHash.of(request("stop", stream = true)))
        assertNull(MessagesHash.of(Json.parseToJsonElement("""{"stream":false}""").jsonObject))
    }

    @Test
    fun `the registry answers each head's own turns and nothing for a head it was not given`() {
        val byHead = LiveTurnsByHead()
        byHead.put("codex", turns)

        assertTrue(byHead.of("codex") === turns)
        assertNull(byHead.of("grok"))
    }
}
