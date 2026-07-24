// Walls for the round runners (eli design 2026-07-24 + code-review fix round): a spliced turn is
// ONE coherent Anthropic message — single message_start, monotonic block indices, exactly ONE
// terminal; budget exhaustion ends in the honest error AFTER the appended blocks; watchdog fires
// and gone clients never continue; the finish outcome carries the WHOLE turn's facts (cross-round
// merge — a round-2 empty completion must not read as an empty model); fold-eligible turns retry
// failed rounds via trigger-B with never-forwarded prose stripped from the salvage.
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.gateway.head.FoldRunner
import splice.gateway.head.ReanchorRunner
import splice.gateway.head.RunnerSignals
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
    val absorbed = mutableListOf<TurnOutcome.Failure>()

    fun signals(watchdog: Boolean = false, gone: Boolean = false) = RunnerSignals(
        watchdogFired = { watchdog },
        clientGone = { gone },
        onRoundFailure = { absorbed.add(it) },
    )

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

private fun retryableFailure(
    outputTokens: Long = 0,
    bodyText: String = "partial",
    thinkingText: String = "",
    emittedText: Boolean = bodyText.isNotEmpty(),
) = TurnOutcome.Failure(
    ErrorType.OVERLOADED,
    "mid-stream death",
    providerReported = true,
    partial = TurnOutcome.PartialRound(
        thinkingText = thinkingText,
        bodyText = bodyText,
        emittedText = emittedText,
        usage = Usage(outputTokens = outputTokens),
    ),
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
            signals = h.signals(),
        ).run(continuationBody(), ReanchorController { continuationBody() })

        assertEquals(1, h.count("message_start"), "exactly one message_start across rounds")
        assertEquals(1, h.count("message_stop"), "exactly one clean terminal")
        assertEquals(0, h.count("error"), "no error frame on a recovered turn")
        assertTrue(h.frames.any { it.contains("\"index\":1") }, "continuation appends a NEW block index")
        val success = h.finished as TurnOutcome.Success
        assertEquals(12, success.usage.outputTokens, "salvaged 7 + final 5")
        assertEquals(1, h.absorbed.size, "the absorbed round failure reached the health hook")
    }

    @Test
    fun `the merged outcome carries the whole turn - a round-2 empty completion is not an empty model`() = runTest {
        val h = Harness()
        val rounds = ArrayDeque<suspend () -> TurnOutcome>()
        rounds.add {
            retryableFailure(outputTokens = 9, bodyText = "the full answer", thinkingText = "the reasoning")
        }
        rounds.add {
            // lost-terminal recovery: the model correctly adds nothing on the continuation
            TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage(outputTokens = 1))
        }
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { rounds.removeFirst().invoke() },
            finish = { h.finish(it) },
            signals = h.signals(),
        ).run(continuationBody(), ReanchorController { continuationBody() })

        val success = h.finished as TurnOutcome.Success
        assertTrue(success.emittedText, "round-1's forwarded text must count for the honesty gate")
        assertEquals("the reasoning", success.thinkingText, "round-1 reasoning must reach the mirror")
        assertEquals("the full answer", success.bodyText)
        assertEquals(10, success.usage.outputTokens)
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
            signals = h.signals(),
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
            signals = h.signals(watchdog = true),
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
    fun `a gone client never continues - no zombie re-POSTs`() = runTest {
        val h = Harness()
        var asks = 0
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { retryableFailure() },
            finish = { h.finish(it) },
            signals = h.signals(gone = true),
        ).run(
            continuationBody(),
            ReanchorController {
                asks++
                continuationBody()
            },
        )
        assertEquals(0, asks, "the controller is never consulted for a gone client")
    }

    @Test
    fun `an ineligible failure surfaces immediately - error, not clean stop`() = runTest {
        val h = Harness()
        ReanchorRunner(
            key = "t",
            log = { },
            postRound = { retryableFailure() },
            finish = { h.finish(it) },
            signals = h.signals(),
        ).run(continuationBody(), ReanchorController { null })
        assertEquals(1, h.count("error"))
        assertEquals(0, h.count("message_stop"))
    }
}

class FoldRunnerReanchorTest {

    @Test
    fun `fold trigger-B retries a failed round with never-forwarded prose stripped from the salvage`() = runTest {
        val h = Harness()
        val seenPartialBodies = mutableListOf<String?>()
        val rounds = ArrayDeque<suspend () -> TurnOutcome>()
        rounds.add { retryableFailure(outputTokens = 3, bodyText = "buffered never-sent prose") }
        rounds.add {
            TurnOutcome.Success(
                hasToolUse = false,
                incomplete = false,
                usage = Usage(outputTokens = 5),
                bodyText = "clean full answer",
                emittedText = true,
            )
        }
        FoldRunner(
            emitter = h.emitter,
            key = "t",
            log = { },
            postRound = { _, _ -> rounds.removeFirst().invoke() },
            finish = { h.finish(it) },
            reanchor = ReanchorController { round ->
                seenPartialBodies.add(round.failure.partial?.bodyText)
                continuationBody()
            },
            signals = h.signals(),
        ).run(continuationBody()) { null }

        assertEquals(listOf(""), seenPartialBodies, "buffered prose must be stripped before the policy sees it")
        val success = h.finished as TurnOutcome.Success
        assertEquals(8, success.usage.outputTokens, "failed round's salvaged usage accrues")
        assertEquals(1, h.count("message_stop"))
        assertEquals(1, h.absorbed.size)
    }

    @Test
    fun `fold trigger-B respects the gone-client gate`() = runTest {
        val h = Harness()
        var asks = 0
        FoldRunner(
            emitter = h.emitter,
            key = "t",
            log = { },
            postRound = { _, _ -> retryableFailure() },
            finish = { h.finish(it) },
            reanchor = ReanchorController {
                asks++
                continuationBody()
            },
            signals = h.signals(gone = true),
        ).run(continuationBody()) { null }
        assertEquals(0, asks)
        assertNull(h.finished as? TurnOutcome.Success)
    }
}
