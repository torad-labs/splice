// NEW: V4-242 (2026-09-26) — the close line's account of what the round had received, which the
// round's tear carries too. A long round is thousands of deltas of a handful of types, so the account
// counts every event and names each type once, in arrival order, up to a cap.
package campaign.v4242

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.dialect.responses.websocket.OpenSockets
import splice.dialect.responses.websocket.WsPulse

class RoundSoFarTest {
    private val pulse = WsPulse("ws-v4242", OpenSockets { 1 })

    @Test
    fun `an idle socket's close ended no round and says nothing about one`() {
        assertNull(pulse.roundSoFar())
    }

    @Test
    fun `a round that received nothing says so`() {
        pulse.roundStarted()
        assertEquals("before any event of the round", pulse.roundSoFar())
    }

    @Test
    fun `every event is counted and each type is named once, in the order it first arrived`() {
        pulse.roundStarted()
        pulse.event("response.created")
        repeat(DELTAS) { pulse.event("response.output_text.delta") }
        pulse.event("response.created")

        assertEquals(
            "after ${DELTAS + 2} events (response.created, response.output_text.delta)",
            pulse.roundSoFar(),
        )
    }

    @Test
    fun `one event reads as one`() {
        pulse.roundStarted()
        pulse.event("codex.rate_limits")
        assertEquals("after 1 event (codex.rate_limits)", pulse.roundSoFar())
    }

    @Test
    fun `types past the cap are counted and marked, never listed`() {
        pulse.roundStarted()
        repeat(TYPES) { pulse.event("t$it") }

        assertEquals("after $TYPES events (t0, t1, t2, t3, t4, t5, t6, t7, …)", pulse.roundSoFar())
    }

    @Test
    fun `a round's account ends with the round and a new round starts empty`() {
        pulse.roundStarted()
        pulse.event("response.created")
        pulse.roundEnded()
        assertNull(pulse.roundSoFar(), "a finished round's events belong to no later close")

        pulse.roundStarted()
        pulse.event("codex.rate_limits")
        assertEquals("after 1 event (codex.rate_limits)", pulse.roundSoFar())
    }

    @Test
    fun `a late frame on an idle socket is no part of the next round`() {
        pulse.roundStarted()
        pulse.roundEnded()
        pulse.event("response.output_text.done")

        pulse.roundStarted()
        assertEquals("before any event of the round", pulse.roundSoFar())
    }
}

private const val DELTAS = 1_000
private const val TYPES = 12
