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
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
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
        reasoning = TurnReasoning(
            showReasoning = ReasoningDisplay.TEXT,
            effort = "high",
            summary = "detailed",
            budgetTokens = null,
        ),
        route = TurnRoute(
            stream = true,
            originalModel = "claude-codex--$model",
            upstreamModel = model,
            clientMaxTokens = 8000,
        ),
        scope = TurnScope(sessionId = session),
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
    fun `concurrent turns in one session keep distinct live ids on their counted slots`() {
        val first = slot()
        val second = slot()
        try {
            turns.admitted(first, meta("shared-session"), hash("same"))
            turns.admitted(second, meta("shared-session"), hash("same"))
            val ids = turns.list().map { it.id }
            assertEquals(2, ids.toSet().size)
            assertEquals(ids, gate.snapshot().live.map { it.turnId })
        } finally {
            first.release()
            second.release()
        }
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
    fun `a held source and its continuation share one live row and stop both jobs`() {
        val original = slot()
        turns.admitted(original, meta("source-session"), hash("go"))
        val first = Job()
        val raw = Job()
        turns.driving(original, first)
        turns.driving(original, raw)
        val lease = original.retainSource("source-session")
        first.complete()
        original.release()
        val resumed = checkNotNull(gate.resumeSource("source-session"))
        val current = Job()
        turns.admitted(resumed, meta("source-session"), hash("result"))
        turns.driving(resumed, current)

        assertEquals(listOf("turn-1"), turns.list().map { it.id }, "one counted source is one listed turn")
        assertEquals("turn-1", gate.snapshot().live.single().turnId, "a borrowed handle keeps its source stop id")
        turns.stop("turn-1")
        assertTrue(causeOf(raw) is OperatorStop)
        assertTrue(causeOf(current) is OperatorStop)
        assertTrue(turns.refusesResend("source-session", request("result", stream = false)))
        resumed.release()
        assertEquals(1, turns.list().size, "the raw owner still holds the live row")
        lease.release()
        assertTrue(turns.list().isEmpty())
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

    /** V4-444: the console hears from a session while its turn is live, however long ago it started, so the registry names
     *  the session of every live turn on every head, and forgets it when the slot is released. */
    @Test
    fun `the registry names the session of every turn live on any head, and drops it on release`() {
        val grok = LiveTurns(clock = ElapsedClock { now }, ids = TurnIdMint { "grok-${++minted}" })
        val byHead = LiveTurnsByHead().apply {
            put("codex", turns)
            put("grok", grok)
        }
        val codexTurn = slot().also { turns.admitted(it, meta("sess-a"), null) }
        val grokTurn = slot().also { grok.admitted(it, meta("sess-b"), null) }
        val anonymous = slot().also { turns.admitted(it, meta(null), null) }
        assertEquals(setOf("sess-a", "sess-b"), byHead.sessions(), "a turn with no session names none")
        codexTurn.release()
        assertEquals(setOf("sess-b"), byHead.sessions())
        grokTurn.release()
        anonymous.release()
        assertEquals(emptySet<String>(), byHead.sessions())
    }

    /** THE ARM THAT MAKES idleMs READABLE AT ALL. Two turns gone quiet for exactly the same time, one
     *  that has never heard from the provider and one that answered first. Their idleMs is identical to
     *  the millisecond, which is the whole problem: the watchdog holds the first against
     *  firstByteTimeout, where two minutes of silence is a model thinking, and the second against the
     *  stall tiers, where the same two minutes is a model that stopped. A console reading idle alone
     *  has to give both the same word, and either word is wrong for one of them. */
    @Test
    fun `two turns silent for the same time are told apart by whether the provider has answered`() {
        val waiting = slot()
        turns.admitted(waiting, meta("sess-prefill"), hash("go"))
        val answering = slot()
        turns.admitted(answering, meta("sess-streaming"), hash("go"))
        answering.received()
        now += 120_000

        val listed = turns.list().associateBy { it.session }

        assertEquals(120_000L, listed.getValue("sess-prefill").silence.idleMs)
        assertEquals(120_000L, listed.getValue("sess-streaming").silence.idleMs, "the same silence, to the ms")
        assertFalse(listed.getValue("sess-prefill").silence.seenOutput, "nothing came back: this one is a prefill")
        assertTrue(listed.getValue("sess-streaming").silence.seenOutput, "this one answered, and then went quiet")
    }

    /** Why the fact is its own flag and not lastByte compared against admission: a fast provider's
     *  first byte lands in the millisecond the turn was admitted, and a derived read would call that
     *  turn unanswered for as long as it ran — wrong for the common case, not the rare one. */
    @Test
    fun `a first byte in the same millisecond as admission still counts as answered`() {
        val fast = slot()
        turns.admitted(fast, meta("sess-fast"), hash("go"))
        fast.received()

        val turn = turns.list().single()

        assertTrue(turn.silence.seenOutput, "the provider answered in that same millisecond, and that is an answer")
        assertEquals(0L, turn.silence.idleMs, "and nothing has gone quiet yet")
    }
}
