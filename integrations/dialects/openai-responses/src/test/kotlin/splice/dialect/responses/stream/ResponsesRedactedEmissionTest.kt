// F141 (review #94): the redacted-path CX-09 contract. Lives outside ResponsesStreamTranslatorTest
// because that class sits AT detekt's LargeClass ceiling — any new test there trips the gate.
// The harness below mirrors that file's RecordingSink/ctx/ev/completed (renamed RecordingWireSink here: private top-level class names must be unique per package) (kept private here:
// the default-package test files already carry same-named private helpers, so sharing them would
// collide; a copy with a header comment is the smaller blast radius).
package splice.dialect.responses.stream

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.SharedSummaryParts
import splice.core.turn.TurnOutcome
import splice.dialect.responses.StreamTurnContext
import splice.dialect.responses.reasoning.EmitEncryptedReasoning
import splice.upstream.sse.WireSink

private class RecordingWireSink : WireSink {
    val calls = mutableListOf<String>()
    private var next = 0

    override suspend fun openText(): WireBlockIndex =
        WireBlockIndex(next++).also { calls.add("openText#${it.value}") }

    override suspend fun openThinking(): WireBlockIndex =
        WireBlockIndex(next++).also { calls.add("openThinking#${it.value}") }

    override suspend fun openTool(id: String, name: String): WireBlockIndex =
        WireBlockIndex(next++).also { calls.add("openTool#${it.value}($id,$name)") }

    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        calls.add("text#${index.value}:$text")
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        calls.add("think#${index.value}:$thinking")
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        calls.add("json#${index.value}:$partialJson")
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        calls.add("close#${index.value}")
    }

    override suspend fun closeAll() {
        calls.add("closeAll")
    }

    override suspend fun addTextBlock(text: String) {
        calls.add("addText:$text")
    }

    override suspend fun addRedactedThinking(data: String) {
        calls.add("redacted:$data")
    }
}

private fun ctx(emit: Boolean) = StreamTurnContext(
    compact = false,
    emitEncryptedReasoning = EmitEncryptedReasoning(emit),
    encodeReasoningEnvelope = { "env:" + it["id"]?.toString().orEmpty() },
    clientGone = { false },
    watchdogFired = { null },
    streamIdleMsForMessage = 180_000,
    upstreamTimeoutMsForMessage = 900_000,
    collectReasoningEnvelopes = false,
    dedupeRepeatedSummaryParts = false,
    summaryPartsShared = SharedSummaryParts(),
    onTurnReasoning = { _, _ -> },
)

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private val completed = ev(
    """{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":100,"output_tokens":7}}}""",
)

class ResponsesRedactedEmissionTest {

    @Test
    fun `redacted-reasoning-only turn is not empty - emittedThinking set on the redacted path`() = runTest {
        // CX-09 follow-up (review #94 F141): emitReplayedReasoning writes a client-visible
        // redacted_thinking block, so a turn whose ONLY wire output is an encrypted reasoning
        // envelope put real content on the wire. TurnPipeline.nothingReachesTheClient reads
        // emittedThinking FIRST — without it this turn is misclassified empty and the client
        // gets an empty_model api_error for a turn that worked.
        val outcome = ResponsesStreamTranslator(ctx(emit = true)).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,
                       "item":{"type":"reasoning","id":"rs_1","encrypted_content":"blob"}}""",
                ),
                completed,
            ).asFlow(),
            RecordingWireSink(),
        )
        assertTrue(
            (outcome as TurnOutcome.Success).emittedThinking,
            "a redacted block reached the sink — the turn is not empty",
        )
    }

    // CX-18/CX-09 retirement (2026-09-21): the two arms below were MISSING, found by mutation
    // rather than by reading. `state.emittedThinking = true` has three write sites in this dialect
    // — ResponsesReasoningReplay.kt:22 (the redacted path, above) and ResponsesReasoningFold.kt's
    // ensureThinkingBlock and appendLateReasoning. Neutering BOTH fold sites left every responses
    // arm green; only the wall's substring token held them, and it is satisfied by any ONE of the
    // three spellings existing anywhere in its file list. A denominator taken from the write sites
    // rather than from the tests is what surfaced it.

    @Test
    fun `a streamed reasoning summary sets emittedThinking - the fold delta path`() = runTest {
        val sink = RecordingWireSink()
        val outcome = ResponsesStreamTranslator(ctx(emit = false)).driveTurn(
            listOf(
                ev(
                    """{"type":"response.reasoning_summary_text.delta","output_index":0,
                       "item_id":"rs_1","delta":"weighing the options"}""",
                ),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue(
            (outcome as TurnOutcome.Success).emittedThinking,
            "a thinking block opened by ensureThinkingBlock reached the sink: calls=${sink.calls}",
        )
        assertTrue(
            sink.calls.any { it.startsWith("openThinking#") },
            "the arm must go through the sink, not merely set a flag: calls=${sink.calls}",
        )
    }

    @Test
    fun `late reasoning on a completed item sets emittedThinking - the fold late path`() = runTest {
        val sink = RecordingWireSink()
        val outcome = ResponsesStreamTranslator(ctx(emit = false)).driveTurn(
            listOf(
                ev(
                    """{"type":"response.output_item.done","output_index":0,
                       "item":{"type":"reasoning","id":"rs_1",
                       "summary":[{"type":"summary_text","text":"late but delivered"}]}}""",
                ),
                completed,
            ).asFlow(),
            sink,
        )
        assertTrue(
            (outcome as TurnOutcome.Success).emittedThinking,
            "reasoning that only arrives with the item terminal still reached the client: calls=${sink.calls}",
        )
        assertTrue(
            sink.calls.any { it.startsWith("openThinking#") },
            "the late path must open a thinking block: calls=${sink.calls}",
        )
    }
}
