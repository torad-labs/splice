// Nov 8: one claudex turn streamed completed reasoning items with no summary and under 2,000 characters of encrypted
// reasoning each, thousands in a row, for 6.3 hours, and never a word or a tool call. These pin what ends such a turn and
// what never does: a model thinking in silence, real reasoning, text or a tool call.
package splice.head.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Lift
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.Tear
import splice.core.turn.TurnOutcome
import splice.core.util.ElapsedClock

private fun event(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

/** A completed reasoning item as the loop sent it: no summary, and [chars] of encrypted reasoning. */
private fun emptyItem(chars: Int = 1_868): JsonObject = event(
    """{"type":"response.output_item.done","item":{"type":"reasoning","summary":[],""" +
        """"encrypted_content":"${"x".repeat(chars)}"}}""",
)

private val SUMMARY_DELTA = event("""{"type":"response.reasoning_summary_text.delta","delta":"Checking the parser"}""")
private val TEXT_DELTA = event("""{"type":"response.output_text.delta","delta":"Here is the fix"}""")
private val TOOL_CALL = event(
    """{"type":"response.output_item.added","item":{"type":"function_call","name":"Bash","arguments":""}}""",
)
private val CONNECTION_LIMIT = event(
    """{"type":"error","error":{"code":"websocket_connection_limit_reached","message":"limit reached"}}""",
)

/** A clock the test moves by hand. */
private class SteppedClock(var nowMs: Long = 1_000_000L) : ElapsedClock {
    override fun invoke(): Long = nowMs
}

class EmptyReasoningRunTest {
    private val clock = SteppedClock()
    private val run = EmptyReasoningRun(clock)

    /** [count] empty items, [gapMs] apart; whether any of them tripped the run. */
    private fun feed(count: Int, gapMs: Long, item: JsonObject = emptyItem()): Boolean {
        var tripped = false
        repeat(count) {
            clock.nowMs += gapMs
            tripped = run.observe(item) || tripped
        }
        return tripped
    }

    @Test
    fun `the Nov 8 loop ends the turn once it has run long enough, across socket rotations`() {
        // About 18 items a minute, as the loop sent them, with the hourly close in the middle of the run.
        assertFalse(feed(50, gapMs = 3_300L), "fifty items in under three minutes is not yet a loop")
        assertFalse(run.observe(CONNECTION_LIMIT), "a socket rotation neither ends nor resets the run")
        assertTrue(feed(150, gapMs = 3_300L), "the run ended the turn")
        val loop = requireNotNull(run.loop)
        assertTrue(loop.items >= EMPTY_RUN_ITEMS && loop.forMs >= EMPTY_RUN_MS, "$loop")
    }

    @Test
    fun `a hundred empty items in a burst are not enough without ten minutes of them`() {
        assertFalse(feed(400, gapMs = 100L), "a short burst of empty items is not a loop")
        assertNull(run.loop)
    }

    @Test
    fun `a model thinking in silence never adds to the run`() {
        clock.nowMs += 6 * 3_600_000L
        assertFalse(run.observe(event("""{"type":"response.in_progress"}""")))
        assertNull(run.loop, "hours of silence are not a loop")
    }

    @Test
    fun `real reasoning, a summary, text or a tool call each start the run over`() {
        val breakers = listOf(emptyItem(chars = 6_000), SUMMARY_DELTA, TEXT_DELTA, TOOL_CALL)
        for (breaker in breakers) {
            val clock = SteppedClock()
            val run = EmptyReasoningRun(clock)
            repeat(30) {
                repeat(EMPTY_RUN_ITEMS - 1) {
                    clock.nowMs += 10_000L
                    run.observe(emptyItem())
                }
                run.observe(breaker)
            }
            assertNull(run.loop, "an hour and more of empty items broken by ${breaker["type"]} never trips")
        }
    }

    @Test
    fun `an item with a summary is not empty, however short its reasoning`() {
        val summarized = event(
            """{"type":"response.output_item.done","item":{"type":"reasoning",""" +
                """"summary":[{"type":"summary_text","text":"Reading the config"}],"encrypted_content":"abc"}}""",
        )
        repeat(500) {
            clock.nowMs += 10_000L
            run.observe(summarized)
        }
        assertNull(run.loop)
    }

    @Test
    fun `the round's failure is told as the run of empty reasoning that ended it`() {
        feed(EMPTY_RUN_ITEMS, gapMs = EMPTY_RUN_MS / (EMPTY_RUN_ITEMS - 1) + 1)
        val torn = TurnOutcome.Failure(
            cause = FailureCause.Path.Torn(Tear.NO_TERMINAL, toolOpen = false),
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence("stream ended with no terminal"),
        )
        val ended = run.ended(torn) as TurnOutcome.Failure
        assertEquals(
            FailureCause.Provider.Empty(reasoningOnly = true, loop = run.loop),
            ended.cause,
        )
        assertTrue("empty reasoning items" in ended.evidence.message, ended.evidence.message)
        assertEquals(torn.at, ended.at)
    }

    @Test
    fun `a round that ends before any run trips keeps its own outcome`() {
        val torn = TurnOutcome.Failure(
            cause = FailureCause.Path.Torn(Tear.RESET, toolOpen = false),
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence("reset"),
        )
        assertEquals(torn, run.ended(torn))
    }

    @Test
    fun `a planned close is told as a rotation, and a tool call it cut stays cut`() {
        val overloaded = TurnOutcome.Failure(
            cause = FailureCause.Provider.Overloaded(Lift.Unstated, loading = false),
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence("websocket_connection_limit_reached"),
            partial = TurnOutcome.PartialRound(toolTearOpen = true),
        )
        val rotated = PlannedClose.ended(overloaded) as TurnOutcome.Failure
        assertEquals(FailureCause.Path.Torn(Tear.ROTATED, toolOpen = true), rotated.cause)
        assertTrue(PlannedClose.isRotation(rotated.cause))
        assertFalse(PlannedClose.isRotation(overloaded.cause))
    }
}
