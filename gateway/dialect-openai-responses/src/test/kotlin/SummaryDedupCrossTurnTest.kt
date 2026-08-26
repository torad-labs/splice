// NEW: cross-TURN recap suppression (2026-08-26). With conversation-lifetime shared state, a NEW
// client turn's stream opens by restating the TAIL of the previous turn's emission (live claudex
// scan: 244/803 thinking messages opened with an already-emitted ordered run, all strictly
// leading). The recap arm must anchor mid-list — a 0-anchored cursor never matches a tail
// restatement — while a genuinely-new following part still lands. Split from
// ResponsesStreamTranslatorTest, which sits at detekt's LargeClass ceiling; the harness below is
// the minimal subset of that file's.
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.SharedSummaryParts
import splice.core.turn.TurnOutcome
import splice.dialect.responses.EmitEncryptedReasoning
import splice.dialect.responses.ResponsesStreamTranslator
import splice.dialect.responses.StreamTurnContext
import splice.spi.WireSink

private class ThinkingRecordingSink : WireSink {
    val calls = mutableListOf<String>()
    private var next = 0

    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        calls.add("text:$text")
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        calls.add("think:$thinking")
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) {
        calls.add("addText:$text")
    }

    override suspend fun addRedactedThinking(data: String) = Unit
}

private fun dedupCtx(shared: SharedSummaryParts) = StreamTurnContext(
    compact = false,
    emitEncryptedReasoning = EmitEncryptedReasoning(false),
    encodeReasoningEnvelope = { "" },
    clientGone = { false },
    watchdogFired = { null },
    streamIdleMsForMessage = 180_000,
    upstreamTimeoutMsForMessage = 900_000,
    dedupeRepeatedSummaryParts = true,
    summaryPartsShared = shared,
)

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private val completed = ev(
    """{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":1,"output_tokens":1}}}""",
)

private fun reasoningRound(vararg parts: String): List<JsonObject> = buildList {
    add(ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}"""))
    parts.forEach {
        add(ev("""{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"$it"}"""))
    }
    add(completed)
}

class SummaryDedupCrossTurnTest {

    @Test
    fun `a new turn's leading tail recap is suppressed against conversation-shared state`() = runTest {
        val p0 = "Planning the prioritized review findings"
        val p1 = "Identifying stale plan conflicts and missing files"
        val p2 = "Reconciling the certification matrix with the runtime"
        val shared = SharedSummaryParts()
        // Turn 1 emits p0 then p1.
        ResponsesStreamTranslator(dedupCtx(shared))
            .driveTurn(reasoningRound(p0, p1).asFlow(), ThinkingRecordingSink())
        // Turn 2 (fresh translator, same conversation state) recaps the TAIL (p1 only), then adds p2.
        val sink = ThinkingRecordingSink()
        val outcome = ResponsesStreamTranslator(dedupCtx(shared))
            .driveTurn(reasoningRound(p1, p2).asFlow(), sink)
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(0, sink.calls.count { it.contains(p1) }, "tail recap leaked: ${sink.calls}")
        assertEquals(1, sink.calls.count { it.contains(p2) }, "genuinely-new part lost: ${sink.calls}")
    }

    @Test
    fun `an anchored recap run that diverges falls back to recording the new part`() = runTest {
        val p0 = "Weighing the two candidate migration designs"
        val p1 = "Confirming the socket chain rebuild ordering"
        val fresh = "Now reconciling the session store mismatch cleanly"
        val shared = SharedSummaryParts()
        ResponsesStreamTranslator(dedupCtx(shared))
            .driveTurn(reasoningRound(p0, p1).asFlow(), ThinkingRecordingSink())
        // Turn 2 anchors on p0, then diverges: the divergent part must land exactly once.
        val sink = ThinkingRecordingSink()
        val outcome = ResponsesStreamTranslator(dedupCtx(shared))
            .driveTurn(reasoningRound(p0, fresh).asFlow(), sink)
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(0, sink.calls.count { it.contains(p0) }, "anchored recap leaked: ${sink.calls}")
        assertEquals(1, sink.calls.count { it.contains(fresh) }, "divergent part lost: ${sink.calls}")
    }
}
