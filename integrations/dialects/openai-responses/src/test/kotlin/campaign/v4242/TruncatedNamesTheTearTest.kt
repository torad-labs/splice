// NEW: V4-242 (2026-09-26) — the truncated ending names the tear that ended the stream, in the words
// of its deepest cause, which after a websocket peer's close is the close itself. A stream that simply
// stopped reads as it always did, and a URL in a tear's words keeps its scheme and host only.
package campaign.v4242

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.SharedSummaryParts
import splice.core.turn.TurnOutcome
import splice.dialect.responses.StreamTurnContext
import splice.dialect.responses.reasoning.EmitEncryptedReasoning
import splice.dialect.responses.stream.ResponsesStreamTranslator
import splice.upstream.sse.WireSink
import java.io.IOException

/** Counts blocks and drops everything else: the ending is what these read. */
private class QuietSink : WireSink {
    private var next = 0

    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++)

    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++)

    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(next++)

    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit

    override suspend fun closeBlock(index: WireBlockIndex) = Unit

    override suspend fun closeAll() = Unit

    override suspend fun addTextBlock(text: String) = Unit

    override suspend fun addRedactedThinking(data: String) = Unit
}

class TruncatedNamesTheTearTest {
    private val ctx = StreamTurnContext(
        compact = false,
        emitEncryptedReasoning = EmitEncryptedReasoning(false),
        encodeReasoningEnvelope = { "" },
        clientGone = { false },
        watchdogFired = { null },
        streamIdleMsForMessage = 180_000,
        upstreamTimeoutMsForMessage = 900_000,
        collectReasoningEnvelopes = false,
        dedupeRepeatedSummaryParts = false,
        summaryPartsShared = SharedSummaryParts(),
        onTurnReasoning = { _, _ -> },
    )

    private suspend fun ending(events: Flow<JsonObject>): String {
        val outcome = ResponsesStreamTranslator(ctx).driveTurn(events, QuietSink())
        return (outcome as TurnOutcome.Failure).message
    }

    private fun tornAfterOutput(tear: IOException): Flow<JsonObject> = flow {
        emit(DELTA)
        throw tear
    }

    @Test
    fun `a peer's close ends the truncated sentence in its own words`() = runTest {
        val tear = IOException("websocket stream ended mid-round", IOException(CLOSE))
        assertEquals(
            "splice: upstream stream ended without response.completed (truncated: $CLOSE); retry",
            ending(tornAfterOutput(tear)),
        )
    }

    @Test
    fun `a tear with no deeper cause is named by its own words`() = runTest {
        assertEquals(
            "splice: upstream stream ended without response.completed " +
                "(truncated: websocket stream ended mid-round); retry",
            ending(tornAfterOutput(IOException("websocket stream ended mid-round"))),
        )
    }

    @Test
    fun `a stream that simply stopped reads as it always did`() = runTest {
        assertEquals(
            "splice: upstream stream ended without response.completed (truncated); retry",
            ending(flowOf(DELTA)),
        )
    }

    @Test
    fun `a URL in a tear's words keeps its scheme and host, never its path or query`() = runTest {
        val tear = IOException(
            "Request timeout has expired [url=https://api.example.test/v1/responses?key=sk-fake-v4242, " +
                "request_timeout=900000 ms]",
        )
        val said = ending(tornAfterOutput(tear))
        assertTrue("url=https://api.example.test," in said, said)
        assertFalse("sk-fake-v4242" in said, "a query can carry a key: $said")
        assertFalse("/v1/responses" in said, said)
    }
}

private val DELTA: JsonObject =
    Json.parseToJsonElement("""{"type":"response.output_text.delta","output_index":0,"delta":"partial"}""").jsonObject

private const val CLOSE =
    "socket closed by the peer (status=1011, no reason given) after 2 events " +
        "(codex.rate_limits, codex.response.metadata)"
