// NEW: sequential_cutoff rendering, ported from codex-rs (2026-08-26; session/turn.rs
// ReasoningSummaryDelta/PartAdded/SummaryDone arms + tests/suite/items.rs
// sequential_cutoff_renders_done_summaries_for_active_reasoning_item). In this mode the backend
// streams MULTIPLE reasoning items concurrently and each item's summary restates the running
// summary; summary DELTAS are noise, and the render surface is reasoning_summary_text.done of
// the ACTIVE item, with splice's conversation-scoped exact-match dedup as the second layer.
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

private class CutoffSink : WireSink {
    val out = mutableListOf<String>()
    private var next = 0
    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        out.add(thinking)
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

private fun cutoffCtx(shared: SharedSummaryParts = SharedSummaryParts()) = StreamTurnContext(
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

private fun added(id: String, oi: Int, type: String = "reasoning"): JsonObject =
    ev("""{"type":"response.output_item.added","output_index":$oi,"item":{"type":"$type","id":"$id"}}""")

private fun itemDone(id: String, oi: Int): JsonObject =
    ev("""{"type":"response.output_item.done","output_index":$oi,"item":{"type":"reasoning","id":"$id"}}""")

private fun summaryDone(id: String, oi: Int, si: Int, text: String): JsonObject =
    ev(
        """{"type":"response.reasoning_summary_text.done","item_id":"$id","output_index":$oi,""" +
            """"summary_index":$si,"text":"$text"}""",
    )

private fun delta(id: String, oi: Int, text: String): JsonObject =
    ev(
        """{"type":"response.reasoning_summary_text.delta","item_id":"$id","output_index":$oi,""" +
            """"delta":"$text"}""",
    )

class SequentialCutoffRenderTest {

    // Mirror of codex's items.rs pin: deltas ignored, done parts of the active item render
    // atomically with a separator, a done arriving after the next item started is stale.
    @Test
    fun `done parts of the active item render once - deltas and stale dones are dropped`() = runTest {
        val p0 = "Planning the zero-downtime migration steps"
        val p1 = "Designing the dual-write trigger strategy"
        val late = "A late step arriving after the message item"
        val sink = CutoffSink()
        val outcome = ResponsesStreamTranslator(cutoffCtx()).driveTurn(
            listOf(
                added("rs_1", 0),
                delta("rs_1", 0, "partial noise the client must never see"),
                summaryDone("rs_1", 0, 0, p0),
                summaryDone("rs_1", 0, 1, p1),
                itemDone("rs_1", 0),
                added("msg_1", 1, type = "message"),
                summaryDone("rs_1", 0, 2, late),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(0, sink.out.count { it.contains("partial noise") }, "delta leaked: ${sink.out}")
        assertEquals(1, sink.out.count { it.contains(p0) })
        assertEquals(1, sink.out.count { it.contains(p1) })
        assertEquals(1, sink.out.count { it == "\n\n" }, "one separator between two parts: ${sink.out}")
        assertEquals(0, sink.out.count { it.contains(late) }, "stale done leaked: ${sink.out}")
    }

    // The concurrent-restatement shape observed live (2026-08-26 capture): a later item restates
    // the earlier item's parts under its OWN id while active — byte-identical, so the dedup layer
    // kills it — and a part the active filter dropped self-heals through the restatement.
    @Test
    fun `a restatement under the active item is deduped and dropped parts self-heal`() = runTest {
        val p0 = "Planning the zero-downtime migration steps"
        val p1 = "Designing the dual-write trigger strategy"
        val sink = CutoffSink()
        val outcome = ResponsesStreamTranslator(cutoffCtx()).driveTurn(
            listOf(
                added("rs_1", 0),
                summaryDone("rs_1", 0, 0, p0),
                itemDone("rs_1", 0),
                added("rs_2", 1),
                // stale: rs_1 lost the active slot — dropped by the id filter
                summaryDone("rs_1", 0, 1, p1),
                // restated under the ACTIVE item — byte-identical, dedup layer suppresses
                summaryDone("rs_2", 1, 0, p0),
                // p1 was dropped above; its restatement under the active item is genuinely
                // unrendered text and lands exactly once
                summaryDone("rs_2", 1, 1, p1),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(1, sink.out.count { it.contains(p0) }, "p0 duplicated or lost: ${sink.out}")
        assertEquals(1, sink.out.count { it.contains(p1) }, "p1 duplicated or lost: ${sink.out}")
    }
}
