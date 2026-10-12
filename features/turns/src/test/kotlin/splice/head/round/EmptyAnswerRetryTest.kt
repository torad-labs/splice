// An empty answer is re-sent once with the same body (empty_model class); a second empty ends as before.
package splice.head.round

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.ResponseShape
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.upstream.RetryBackoff
import splice.upstream.RoundResult
import splice.upstream.sse.WireSink

class EmptyAnswerRetryTest {
    private val empty = success(RoundText(), usage = Usage(200_000, 0, 0))
    private val answered = success(RoundText(bodyText = "hello", emittedText = true))

    @Test
    fun `an upstream that answers empty once and then with content ends on the content`() = runTest {
        val perf = TurnPerf()
        val run = drive(perf, listOf(empty, answered))

        assertSame(answered, run.finished.single())
        assertEquals(listOf(BODY, BODY), run.bodies, "the same bytes both times")
        val billed = (run.finished.single() as TurnOutcome.Success).usage
        assertEquals(1, billed.absorbed.rounds, "the empty round is metered as a request of its own")
        assertEquals(200_000L, billed.absorbed.inputTokens)
        assertEquals(1L, perf.count(PerfKeys.EMPTY_RETRIES))
    }

    @Test
    fun `an upstream that is always empty is asked exactly twice and ends empty`() = runTest {
        val perf = TurnPerf()
        val run = drive(perf, listOf(empty, empty, answered))

        assertSame(empty, run.finished.single())
        assertEquals(2, run.bodies.size)
        assertEquals(1L, perf.count(PerfKeys.EMPTY_RETRIES))
    }

    @Test
    fun `a closed empty message is a finished answer and is asked once`() = runTest {
        val closed = success(RoundText(), ResponseShape(messageClosed = true))
        val run = drive(TurnPerf(), listOf(closed, answered))

        assertSame(closed, run.finished.single())
        assertEquals(1, run.bodies.size)
    }

    @Test
    fun `a round that answered is asked once`() = runTest {
        val run = drive(TurnPerf(), listOf(answered, empty))

        assertSame(answered, run.finished.single())
        assertEquals(1, run.bodies.size)
    }

    private data class Run(val finished: List<TurnOutcome>, val bodies: List<String>)

    private suspend fun drive(perf: TurnPerf, replies: List<TurnOutcome>): Run {
        val finished = mutableListOf<TurnOutcome>()
        val bodies = mutableListOf<String>()
        RoundStrategy(
            emitter = QuietSink(),
            runners = RoundRunners(
                backoff = RetryBackoff { _, _ -> },
                key = "test",
                log = {},
                signals = RunnerSignals(),
                finish = { finished += it },
            ),
            postRoundToSink = { _, _ -> error("single round must not buffer") },
            postRound = { body ->
                bodies += body.text
                RoundResult.Outcome(replies[bodies.size - 1])
            },
        ).run(JsonObject(emptyMap()), fold = null, reanchor = null, perf = perf)
        return Run(finished, bodies)
    }

    private fun success(text: RoundText, shape: ResponseShape = ResponseShape(), usage: Usage = Usage(10, 0, 0)) =
        TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = usage, text = text, shape = shape)

    private class QuietSink : WireSink {
        override suspend fun openText(): WireBlockIndex = error("unexpected sink write")
        override suspend fun openThinking(): WireBlockIndex = error("unexpected sink write")
        override suspend fun openTool(id: String, name: String): WireBlockIndex = error("unexpected sink write")
        override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
        override suspend fun closeBlock(index: WireBlockIndex) = Unit
        override suspend fun closeAll() = Unit
        override suspend fun addTextBlock(text: String) = Unit
        override suspend fun addRedactedThinking(data: String) = Unit
    }

    private companion object {
        const val BODY = "{}"
    }
}
