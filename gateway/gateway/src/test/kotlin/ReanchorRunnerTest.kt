// Walls for the ReanchorRunner (eli design 2026-07-24): a spliced turn is ONE coherent Anthropic
// message — single message_start, monotonic block indices across rounds, exactly ONE terminal;
// budget exhaustion ends in the honest error AFTER the appended blocks; a watchdog fire never
// continues. The real SseEmitter is the wall — its seal is what makes the invariants structural.
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.gateway.head.ReanchorRunner
import splice.gateway.wire.SseEmitter
import splice.spi.ReanchorController

private fun continuationBody() = buildJsonObject { }

private class Harness {
    val frames = mutableListOf<String>()
    val emitter = SseEmitter.create(
        write = { frames.add(it) },
        model = "m",
        usagePayload = { buildJsonObject { } },
        messageId = "msg_test",
    )
    var finished: TurnOutcome? = null

    suspend fun finish(outcome: TurnOutcome) {
        finished = outcome
        when (outcome) {
            is TurnOutcome.Success -> emitter.emitTerminal(outcome.hasToolUse, outcome.incomplete, outcome.usage)
            is TurnOutcome.Failure -> emitter.emitError(outcome.type, outcome.message)
            TurnOutcome.ClientAbandoned -> emitter.abandon()
        }
    }

    fun count(event: String) = frames.count { it.startsWith("event: $event\n") }
}

private fun retryableFailure(outputTokens: Long = 0) = TurnOutcome.Failure(
    ErrorType.OVERLOADED,
    "mid-stream death",
    partial = TurnOutcome.PartialRound(bodyText = "partial", usage = Usage(outputTokens = outputTokens)),
)

class ReanchorRunnerTest {

    @Test
    fun `continuation splices one coherent message with a single clean terminal`() = runTest {
        val h = Harness()
        val rounds = ArrayDeque<suspend () -> TurnOutcome>()
        rounds.add {
            val i = h.emitter.openText()
            h.emitter.textDelta(i, "part one")
            h.emitter.closeBlock(i)
            retryableFailure(outputTokens = 7)
        }
        rounds.add {
            val i = h.emitter.openText()
            h.emitter.textDelta(i, " part two")
            h.emitter.closeBlock(i)
            TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage(outputTokens = 5))
        }
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { rounds.removeFirst().invoke() },
            finish = { h.finish(it) },
            watchdogFired = { false },
        ).run(continuationBody(), ReanchorController { continuationBody() })

        assertEquals(1, h.count("message_start"), "exactly one message_start across rounds")
        assertEquals(1, h.count("message_stop"), "exactly one clean terminal")
        assertEquals(0, h.count("error"), "no error frame on a recovered turn")
        // monotonic indices: round 2's block is index 1, never a reused 0
        assertTrue(h.frames.any { it.contains("\"content_block_start\",\"index\":0") || it.contains("\"index\":0") })
        assertTrue(h.frames.any { it.contains("\"index\":1") }, "continuation appends a NEW block index")
        // salvaged output tokens ride the final usage
        val success = h.finished as TurnOutcome.Success
        assertEquals(12, success.usage.outputTokens, "salvaged 7 + final 5")
    }

    @Test
    fun `budget exhaustion surfaces the honest error after the appended blocks`() = runTest {
        val h = Harness()
        var posts = 0
        var asks = 0
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = {
                posts++
                val i = h.emitter.openText()
                h.emitter.textDelta(i, "chunk $posts")
                h.emitter.closeBlock(i)
                retryableFailure()
            },
            finish = { h.finish(it) },
            watchdogFired = { false },
        ).run(
            continuationBody(),
            ReanchorController { round ->
                asks++
                if (round.attempt < 2) continuationBody() else null
            },
        )

        assertEquals(3, posts, "two continuations spent, third failure surfaces")
        assertEquals(3, asks)
        assertEquals(1, h.count("error"), "the honest error terminal")
        assertEquals(0, h.count("message_stop"), "never a fabricated clean stop")
        assertTrue(h.finished is TurnOutcome.Failure)
    }

    @Test
    fun `a watchdog fire never continues - its cancellation owns the turn`() = runTest {
        val h = Harness()
        var asks = 0
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { retryableFailure() },
            finish = { h.finish(it) },
            watchdogFired = { true },
        ).run(
            continuationBody(),
            ReanchorController {
                asks++
                continuationBody()
            },
        )
        assertEquals(0, asks, "the controller is never consulted after a watchdog fire")
        assertEquals(1, h.count("error"))
    }

    @Test
    fun `an ineligible failure surfaces immediately - error, not clean stop`() = runTest {
        val h = Harness()
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { retryableFailure() },
            finish = { h.finish(it) },
            watchdogFired = { false },
        ).run(continuationBody(), ReanchorController { null })
        assertEquals(1, h.count("error"))
        assertEquals(0, h.count("message_stop"))
    }
}
