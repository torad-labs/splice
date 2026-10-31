// What a translator writes on a flow that ended TORN must not reach the client, or the attempt stops being
// recoverable. Driven against the real chat translator, which is the writer the regression found: its closing
// flushes the tool calls it was still holding, so an upstream that sends a tool-call id and an opening-brace
// argument fragment and then tears gets that half-built call opened and fed on the way out. A client that has been
// shown content cannot be served a silent reissue, so the tool frame alone turned a recoverable tear into an error
// the operator reads. The upstream flow here ends the way a torn read ends it — completed, with the ending recorded
// on [RoundEnd] — which is the ending SseRoundConsume reads after the translator returns.
package splice.head.transport

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.dialect.chat.ChatStreamTranslator
import splice.dialect.chat.ChatTurnContext
import splice.head.RecordingSink2
import java.io.IOException

private const val IDLE_CAP_MS = 180_000L
private const val TOTAL_CAP_MS = 900_000L

/** The shape the regression found: an id and an opening brace, with no function name and no arguments to parse. */
private const val HALF_BUILT_TOOL_CALL =
    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function",""" +
        """"function":{"arguments":"{"}}]}}]}"""

private fun event(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

private fun translator() = ChatStreamTranslator(ChatTurnContext({ false }, { null }, IDLE_CAP_MS, TOTAL_CAP_MS))

class RoundEndTornGateTest {

    @Test
    fun `a torn ending shows the client nothing the translator writes on its way out`() = runTest {
        val sink = RecordingSink2()
        val end = RoundEnd()
        end.torn = IOException("connection reset by peer")

        translator().driveTurn(listOf(event(HALF_BUILT_TOOL_CALL)).asFlow(), end.gate(sink))

        assertEquals(emptyList<String>(), sink.opens, "a torn attempt opens no block on the client")
        assertEquals(emptyList<String>(), sink.jsonDeltas, "and feeds it no tool arguments")
    }

    @Test
    fun `an ending that never came leaves the same writes on the client`() = runTest {
        val sink = RecordingSink2()
        val end = RoundEnd()

        translator().driveTurn(listOf(event(HALF_BUILT_TOOL_CALL)).asFlow(), end.gate(sink))

        assertEquals(listOf("tool:tool"), sink.opens, "an untorn attempt still flushes what it was holding")
        assertEquals(listOf("{"), sink.jsonDeltas, "arguments and all — this is the pre-regression behavior")
    }
}
