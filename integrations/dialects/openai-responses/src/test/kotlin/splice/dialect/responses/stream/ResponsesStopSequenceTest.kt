// NEW (Nov 14, 2026): the client's stop strings on a backend with no stop parameter. Claude Code's auto-mode review
// asks for `["</block>"]` and reads the verdict before it; the dialect used to refuse the request outright.
package splice.dialect.responses.stream

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private fun delta(text: String) = ev("""{"type":"response.output_text.delta","output_index":0,"delta":"$text"}""")

private val messageAdded = ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""")

/** The message item done, carrying [text] and nothing past it. */
private fun messageDone(text: String) = ev(
    """{"type":"response.output_item.done","output_index":0,"item":{"type":"message",
       "content":[{"type":"output_text","text":"$text"}]}}""",
)

private val completed = ev(
    """{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":100,"output_tokens":7}}}""",
)

/** A completed response whose snapshot holds the message [text]: the backend's final word on the answer. */
private fun completedWith(text: String) = ev(
    """{"type":"response.completed","response":{"id":"r1","output":[{"type":"message",
       "content":[{"type":"output_text","text":"$text"}]}],"usage":{"input_tokens":100,"output_tokens":7}}}""",
)

class ResponsesStopSequenceTest {

    private fun review() = ResponsesStreamTranslator(ctx(), stopSequences = listOf("</block>"))

    @Test
    fun `a stop split across two deltas is never half shown, and the turn ends on it`() = runTest {
        val sink = RecordingSink()
        val outcome = review().driveTurn(
            listOf(messageAdded, delta("<block>no</bl"), delta("ock> because it was asked"), completed).asFlow(),
            sink,
        ) as TurnOutcome.Success
        assertEquals("<block>no", shownText(sink))
        assertEquals("</block>", outcome.stopSequence)
        assertEquals("<block>no", outcome.text.bodyText)
    }

    @Test
    fun `an answer that runs into its cap after the stop ended at the stop`() = runTest {
        val outcome = review().driveTurn(
            listOf(
                messageAdded,
                delta("<block>yes</block> and then more words"),
                ev(
                    """{"type":"response.incomplete","response":{"status":"incomplete",
                       "incomplete_details":{"reason":"max_output_tokens"}}}""",
                ),
            ).asFlow(),
            RecordingSink(),
        ) as TurnOutcome.Success
        assertEquals("</block>", outcome.stopSequence)
        assertFalse(outcome.incomplete, "the client's answer ended at its stop string, not at the cap")
    }

    @Test
    fun `text that only looked like the start of a stop is shown when the answer ends`() = runTest {
        val sink = RecordingSink()
        val outcome = review().driveTurn(
            listOf(messageAdded, delta("a</b"), delta("r"), completed).asFlow(),
            sink,
        ) as TurnOutcome.Success
        assertEquals("a</br", shownText(sink))
        assertNull(outcome.stopSequence)
        assertEquals("a</br", outcome.text.bodyText)
    }

    @Test
    fun `a stop split between the stream and the final snapshot is found, and nothing past it is shown`() = runTest {
        val sink = RecordingSink()
        val outcome = review().driveTurn(
            listOf(messageAdded, delta("ok</bl"), completedWith("ok</block>extra")).asFlow(),
            sink,
        ) as TurnOutcome.Success
        assertEquals("ok", shownText(sink))
        assertEquals("</block>", outcome.stopSequence)
        assertEquals("ok", outcome.text.bodyText)
    }

    @Test
    fun `a stop split between a message item that closed and the final snapshot is found`() = runTest {
        val sink = RecordingSink()
        val outcome = review().driveTurn(
            listOf(messageAdded, delta("ok</bl"), messageDone("ok</bl"), completedWith("ok</block>extra")).asFlow(),
            sink,
        ) as TurnOutcome.Success
        assertEquals("ok", shownText(sink))
        assertTrue(sink.calls.none { "</bl" in it || "extra" in it }, "nothing from the stop on reaches the client")
        assertEquals("</block>", outcome.stopSequence)
        assertEquals("ok", outcome.text.bodyText)
    }

    @Test
    fun `text a closed message item held is shown when the answer ends with no stop after it`() = runTest {
        val sink = RecordingSink()
        val outcome = review().driveTurn(
            listOf(messageAdded, delta("a</b"), messageDone("a</b"), completedWith("a</b")).asFlow(),
            sink,
        ) as TurnOutcome.Success
        assertTrue(sink.calls.any { "</b" in it }, "the held text reaches the client")
        assertNull(outcome.stopSequence)
    }

    @Test
    fun `an answer sent only in the final snapshot is cut at its stop`() = runTest {
        val sink = RecordingSink()
        val outcome = review().driveTurn(listOf(completedWith("<block>no</block> because")).asFlow(), sink)
            as TurnOutcome.Success
        assertEquals("", shownText(sink), "the snapshot's answer reaches the client from the outcome, not the stream")
        assertEquals("<block>no", outcome.text.bodyText)
        assertEquals("</block>", outcome.stopSequence)
    }

    @Test
    fun `a turn with no stop strings streams every delta as it comes`() = runTest {
        val sink = RecordingSink()
        val events = listOf(messageAdded, delta("<block>no</bl"), completed)
        ResponsesStreamTranslator(ctx()).driveTurn(events.asFlow(), sink)
        assertEquals(listOf("text#0:<block>no</bl"), sink.calls.filter { it.startsWith("text#") })
    }

    /** Every text delta the client was sent, joined. */
    private fun shownText(sink: RecordingSink): String =
        sink.calls.filter { it.startsWith("text#") }.joinToString("") { it.substringAfter(':') }
}
