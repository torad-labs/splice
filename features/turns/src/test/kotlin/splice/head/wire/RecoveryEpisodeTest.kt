// NEW: failure-handling slice 2 (2026-10-26). A committed stream shows one line per recovery episode
// (final-design.md section 8, "Lines from waits", rules 3 to 5): the first failure of an episode says what the turn
// met and what it does next, a failure that follows before the client has seen a word more says nothing it has not
// already said, and an episode ends when model content reaches the client again. Quiet lines keep their heartbeat
// inside an episode; only repeated failures go silent.
package splice.head.wire

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.QuietTurn
import splice.core.failure.Recovery
import splice.core.failure.ServerFault
import splice.core.failure.WaitLine
import splice.core.turn.Usage
import splice.head.testVoice

class RecoveryEpisodeTest {
    private val frames = mutableListOf<String>()
    private val emitter = SseEmitterFactory().create(
        write = { frames.add(it) },
        model = "m",
        usagePayload = { buildJsonObject { put("input_tokens", 0) } },
        streaming = StreamWiring(messageId = "msg_fixed"),
        voice = testVoice,
    )

    /** A server error that broke the answer off, which the turn picks up from where it stopped. */
    private val midAnswer = WaitLine(FailureCause.Provider.ServerError(ServerFault.INTERNAL), Recovery.Resume)

    /** An empty answer, which the turn asks for again. */
    private val empty = WaitLine(FailureCause.Provider.Empty(reasoningOnly = false), Recovery.AskAgain)

    /** Every [splice] line the client was shown, in the order it was shown. */
    private fun lines(): List<String> = frames
        .filter { it.startsWith("event: content_block_delta") && "thinking_delta" in it }
        .map { frame ->
            val data = frame.lineSequence().first { it.startsWith("data: ") }.removePrefix("data: ")
            Json.parseToJsonElement(
                data,
            ).jsonObject.getValue("delta").jsonObject.getValue("thinking").jsonPrimitive.content
        }
        .flatMap { it.split('\n') }
        .filter { it.startsWith("[splice] ") }

    @Test
    fun `failures that follow one another before a word more reaches the client show one line`() = runTest {
        emitter.ensureStarted()

        repeat(3) { emitter.recovering(midAnswer) }

        assertEquals(listOf(testVoice.waiting(midAnswer.cause, midAnswer.next).text), lines())
    }

    @Test
    fun `model content ends the episode, so the next failure is told again`() = runTest {
        emitter.ensureStarted()
        emitter.recovering(midAnswer)
        val answer = emitter.openText()
        emitter.textDelta(answer, "more of the answer")
        emitter.recovering(midAnswer)

        val line = testVoice.waiting(midAnswer.cause, midAnswer.next).text
        assertEquals(listOf(line, line), lines(), "a second episode opens with its own line: $frames")
    }

    @Test
    fun `an episode is one episode whatever the failures in it were`() = runTest {
        emitter.ensureStarted()
        emitter.recovering(empty)
        emitter.recovering(midAnswer)

        assertEquals(listOf(testVoice.waiting(empty.cause, empty.next).text), lines())
    }

    @Test
    fun `quiet lines keep their heartbeat inside an episode`() = runTest {
        emitter.ensureStarted()
        emitter.recovering(midAnswer)
        emitter.progress { fresh -> testVoice.quiet(QuietTurn(60_000, answering = true, again = !fresh)) }

        assertEquals(
            listOf(
                testVoice.waiting(midAnswer.cause, midAnswer.next).text,
                testVoice.quiet(QuietTurn(60_000, answering = true, again = true)).text,
            ),
            lines(),
            "the episode silences repeated failures, never the heartbeat",
        )
    }

    @Test
    fun `nothing is told once the turn has ended`() = runTest {
        emitter.ensureStarted()
        emitter.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage())

        emitter.recovering(midAnswer)

        assertEquals(emptyList<String>(), lines())
    }
}
