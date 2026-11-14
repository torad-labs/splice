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
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private fun delta(text: String) = ev("""{"type":"response.output_text.delta","output_index":0,"delta":"$text"}""")

private val messageAdded = ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""")

private val completed = ev(
    """{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":100,"output_tokens":7}}}""",
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
