// NEW: the 2026-07-26 mirror-duplication fix, turn-scoped. Continuation rounds (fold / reanchor /
// tool_search) re-request the detailed reasoning summary (operator: "always show the reasoning,
// detailed"), and the backend restates already-summarized sections under FRESH item ids — which
// the codex-parity active-item filter cannot catch (the fresh item IS active in its round).
// Per-round translators each had their own dedup state, so the repeat passed every round.
// SharedSummaryParts makes the dedup shared across rounds: exact repeats die, genuinely-new
// sections always pass. Reworked 2026-08-26 for the sequential_cutoff port: parts arrive as
// reasoning_summary_text.done events (the mode's only render surface), not deltas.
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.SharedSummaryParts
import splice.dialect.responses.EmitEncryptedReasoning
import splice.dialect.responses.ResponsesStreamTranslator
import splice.dialect.responses.StreamTurnContext
import splice.spi.WireSink

private class Sink : WireSink {
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

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        calls.add("json:$partialJson")
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        calls.add("close")
    }

    override suspend fun closeAll() {
        calls.add("closeAll")
    }

    override suspend fun addTextBlock(text: String) {
        calls.add("addText:$text")
    }

    override suspend fun addRedactedThinking(data: String) {
        calls.add("redacted")
    }
}

private fun ctx(shared: SharedSummaryParts) = StreamTurnContext(
    compact = false,
    emitEncryptedReasoning = EmitEncryptedReasoning(false),
    encodeReasoningEnvelope = { null },
    clientGone = { false },
    watchdogFired = { null },
    streamIdleMsForMessage = 180_000,
    upstreamTimeoutMsForMessage = 900_000,
    dedupeRepeatedSummaryParts = true,
    summaryPartsShared = shared,
)

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private val completed = ev(
    """{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":10,"output_tokens":7}}}""",
)

private fun reasoningRound(id: String, vararg sections: String) = buildList {
    add(ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"$id"}}"""))
    sections.forEachIndexed { i, s ->
        add(
            ev(
                """{"type":"response.reasoning_summary_text.done","item_id":"$id","output_index":0,""" +
                    """"summary_index":$i,"text":"$s"}""",
            ),
        )
    }
    add(completed)
}.asFlow()

class SummaryTurnDedupTest {

    @Test
    fun `continuation round's re-titled section is suppressed turn-scoped, new sections pass`() = runTest {
        val shared = SharedSummaryParts()
        val section = "**Analyzing CLI exit and async error handling**"
        val fresh = "**Deploying the hardened fleet build**"

        val r1 = Sink()
        ResponsesStreamTranslator(ctx(shared)).driveTurn(reasoningRound("rs_a", section), r1)
        assertEquals(1, r1.calls.count { it.contains(section) }, "round 1 must emit its section once")

        // round 2 (continuation round, FRESH translator, shared turn state): the backend restates
        // the same section under a FRESH item id (active in its round, so the codex filter passes
        // it — only the shared text match can kill it), then adds a genuinely new one
        val r2 = Sink()
        ResponsesStreamTranslator(ctx(shared)).driveTurn(reasoningRound("rs_b", section, fresh), r2)
        assertEquals(0, r2.calls.count { it.contains(section) }, "re-titled section leaked: ${r2.calls}")
        assertEquals(1, r2.calls.count { it.contains(fresh) }, "new section wrongly suppressed: ${r2.calls}")
    }

    @Test
    fun `without shared state the same repeat passes (the bug this fixes)`() = runTest {
        val section = "**Analyzing CLI exit and async error handling**"
        val r1 = Sink()
        ResponsesStreamTranslator(ctx(SharedSummaryParts())).driveTurn(reasoningRound("rs_a", section), r1)
        val r2 = Sink()
        ResponsesStreamTranslator(ctx(SharedSummaryParts())).driveTurn(reasoningRound("rs_b", section), r2)
        assertEquals(
            1,
            r2.calls.count { it.contains(section) },
            "isolated round state must let the repeat through — pinning why the shared holder exists",
        )
    }
}
