// NEW: cross-TURN restatement suppression (2026-08-26, reworked for the sequential_cutoff
// codex-parity port). A new client turn's stream restates the prior turn's summary parts as
// done-events under FRESH item ids (which are active, so the id filter alone cannot drop them);
// the conversation-lifetime SharedSummaryParts is what suppresses them — byte-identical parts
// are restatements (the 2026-07-26 no-duplicates-wins ruling). Live baseline: 25% of all claudex
// thinking messages opened with an already-emitted run before this state existed.
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
import splice.dialect.responses.SummaryRoundScope
import splice.spi.WireSink

private class ThinkingRecordingSink : WireSink {
    val calls = mutableListOf<String>()
    private var next = 0
    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        calls.add(thinking)
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

private fun dedupCtx(
    shared: SharedSummaryParts,
    scope: SummaryRoundScope = SummaryRoundScope(shared),
) = StreamTurnContext(
    compact = false,
    emitEncryptedReasoning = EmitEncryptedReasoning(false),
    encodeReasoningEnvelope = { "" },
    clientGone = { false },
    watchdogFired = { null },
    streamIdleMsForMessage = 180_000,
    upstreamTimeoutMsForMessage = 900_000,
    dedupeRepeatedSummaryParts = true,
    summaryPartsShared = shared,
    summaryRoundScope = scope,
)

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private val completed = ev(
    """{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":1,"output_tokens":1}}}""",
)

/** One reasoning item (fresh [id]) delivering [parts] as done-events — the cutoff wire shape. */
private fun reasoningRoundAt(outputIndex: Int, id: String, vararg parts: String): List<JsonObject> = buildList {
    add(
        ev(
            """{"type":"response.output_item.added","output_index":$outputIndex,""" +
                """"item":{"type":"reasoning","id":"$id"}}""",
        ),
    )
    parts.forEachIndexed { i, p ->
        add(
            ev(
                """{"type":"response.reasoning_summary_text.done","item_id":"$id",""" +
                    """"output_index":$outputIndex,"summary_index":$i,"text":"$p"}""",
            ),
        )
    }
    add(completed)
}

private fun reasoningRound(id: String, vararg parts: String): List<JsonObject> =
    reasoningRoundAt(0, id, *parts)

class SummaryDedupCrossTurnTest {

    @Test
    fun `a new turn's leading tail restatement is suppressed against conversation-shared state`() = runTest {
        val p0 = "Planning the prioritized review findings"
        val p1 = "Identifying stale plan conflicts and missing files"
        val p2 = "Reconciling the certification matrix with the runtime"
        val shared = SharedSummaryParts()
        // Turn 1 emits p0 then p1.
        ResponsesStreamTranslator(dedupCtx(shared))
            .driveTurn(reasoningRound("rs_t1", p0, p1).asFlow(), ThinkingRecordingSink())
        // Turn 2 (fresh translator + fresh item id, same conversation state) restates the TAIL
        // (p1), then adds p2. The restating item IS active, so only the shared state can drop it.
        val sink = ThinkingRecordingSink()
        val outcome = ResponsesStreamTranslator(dedupCtx(shared))
            .driveTurn(reasoningRound("rs_t2", p1, p2).asFlow(), sink)
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(0, sink.calls.count { it.contains(p1) }, "tail restatement leaked: ${sink.calls}")
        assertEquals(1, sink.calls.count { it.contains(p2) }, "genuinely-new part lost: ${sink.calls}")
    }

    @Test
    fun `an anchored restatement run that diverges falls back to recording the new part`() = runTest {
        val p0 = "Weighing the two candidate migration designs"
        val p1 = "Confirming the socket chain rebuild ordering"
        val fresh = "Now reconciling the session store mismatch cleanly"
        val shared = SharedSummaryParts()
        ResponsesStreamTranslator(dedupCtx(shared))
            .driveTurn(reasoningRound("rs_t1", p0, p1).asFlow(), ThinkingRecordingSink())
        // Turn 2 anchors on p0, then diverges: the divergent part must land exactly once.
        val sink = ThinkingRecordingSink()
        val outcome = ResponsesStreamTranslator(dedupCtx(shared))
            .driveTurn(reasoningRound("rs_t2", p0, fresh).asFlow(), sink)
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(0, sink.calls.count { it.contains(p0) }, "anchored restatement leaked: ${sink.calls}")
        assertEquals(1, sink.calls.count { it.contains(fresh) }, "divergent part lost: ${sink.calls}")
    }

    @Test
    fun `an older turn's matching procedure titles do not suppress a new procedure`() = runTest {
        val p0 = "Reviewing the migration plan and affected files"
        val p1 = "Applying the safe write before the navigation step"
        val filler0 = "Investigating an unrelated websocket lifecycle failure"
        val filler1 = "Verifying the replacement socket closes exactly once"
        val shared = SharedSummaryParts()
        ResponsesStreamTranslator(dedupCtx(shared))
            .driveTurn(reasoningRoundAt(0, "rs_old", p0, p1).asFlow(), ThinkingRecordingSink())
        ResponsesStreamTranslator(dedupCtx(shared))
            .driveTurn(reasoningRoundAt(1, "rs_middle", filler0, filler1).asFlow(), ThinkingRecordingSink())

        val sink = ThinkingRecordingSink()
        val outcome = ResponsesStreamTranslator(dedupCtx(shared))
            .driveTurn(reasoningRoundAt(2, "rs_new", p0, p1).asFlow(), sink)

        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(1, sink.calls.count { it.contains(p0) }, "old title suppressed new work: ${sink.calls}")
        assertEquals(1, sink.calls.count { it.contains(p1) }, "old title suppressed new work: ${sink.calls}")
    }

    // Regression guard (2026-07-20): a paragraph two DISTINCT items genuinely share byte-for-byte
    // is not a recap when the second item's own new part came first. A turn-global seen set dropped
    // it and starved the second item's summary.
    @Test
    fun `a paragraph two distinct items coincidentally share is kept, not cross-dropped`() = runTest {
        val p0 = "Weighing the two candidate migration designs"
        val sharedParagraph = "The token write must precede the navigation step"
        val fresh = "Now reconciling the session store mismatch cleanly"
        val events = buildList {
            addAll(reasoningRoundAt(0, "rs_first", p0, sharedParagraph).dropLast(1))
            addAll(reasoningRoundAt(1, "rs_second", fresh, sharedParagraph))
        }
        val sink = ThinkingRecordingSink()
        val outcome = ResponsesStreamTranslator(dedupCtx(SharedSummaryParts()))
            .driveTurn(events.asFlow(), sink)

        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(1, sink.calls.count { it.contains(p0) })
        assertEquals(1, sink.calls.count { it.contains(fresh) })
        assertEquals(
            2,
            sink.calls.count { it.contains(sharedParagraph) },
            "shared paragraph wrongly dropped: ${sink.calls}",
        )
    }
}
