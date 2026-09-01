// Walls for mid-stream re-anchoring (eli design 2026-07-24): the poison tear and committed-tool
// rounds MUST refuse continuation (fall back to the honest error terminal); the happy path builds
// a continuation whose input appends reasoning replay + the partial prose + the resume marker.
// The translator side pins the salvage payload: what a failed round carries, and when it must not.
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.SharedSummaryParts
import splice.core.turn.TurnOutcome
import splice.dialect.responses.EmitEncryptedReasoning
import splice.dialect.responses.ResponsesReanchorController
import splice.dialect.responses.ResponsesStreamTranslator
import splice.dialect.responses.StreamTurnContext
import splice.spi.ReanchorRound
import splice.spi.WatchdogFired
import splice.spi.WireSink

private fun previousBody(): JsonObject = Json.parseToJsonElement(
    """{"model":"gpt-5.6-sol","input":[{"role":"user","content":"hi"}],"store":false,"stream":true}""",
).jsonObject

private fun failureWith(
    type: ErrorType = ErrorType.OVERLOADED,
    partial: TurnOutcome.PartialRound? = TurnOutcome.PartialRound(bodyText = "The fix is to"),
) = TurnOutcome.Failure(type, "boom", partial = partial)

private val controller = ResponsesReanchorController(
    decodeReasoningEnvelope = { env ->
        Json.parseToJsonElement("""{"type":"reasoning","id":"$env"}""").jsonObject
    },
)

class ResponsesReanchorControllerTest {

    @Test
    fun `overloaded mid-text failure yields replay + partial prose + resume marker`() {
        val partial = TurnOutcome.PartialRound(
            bodyText = "The fix is to",
            reasoningEnvelopes = listOf("e1"),
        )
        val next = controller.continuationForFailure(
            ReanchorRound(previousBody(), failureWith(partial = partial), attempt = 0),
        )
        assertNotNull(next)
        val input = next!!["input"]!!.jsonArray
        // user + decoded replay + assistant partial + marker
        assertEquals(4, input.size)
        assertEquals("e1", input[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("The fix is to", input[2].jsonObject["content"]!!.jsonPrimitive.content)
        val marker = input[3].jsonObject
        assertEquals("commentary", marker["phase"]!!.jsonPrimitive.content)
        assertTrue(marker["content"]!!.jsonPrimitive.content.contains("interrupted"))
    }

    @Test
    fun `a poison tool tear refuses continuation`() {
        val partial = TurnOutcome.PartialRound(bodyText = "x", hasToolUse = true, toolTearOpen = true)
        assertNull(
            controller.continuationForFailure(
                ReanchorRound(previousBody(), failureWith(partial = partial), 0),
            ),
        )
    }

    @Test
    fun `a committed tool_use refuses continuation - no orphan function_call, no double dispatch`() {
        val partial = TurnOutcome.PartialRound(
            bodyText = "x",
            hasToolUse = true,
        )
        assertNull(
            controller.continuationForFailure(
                ReanchorRound(previousBody(), failureWith(partial = partial), 0),
            ),
        )
    }

    @Test
    fun `non-retryable failure types refuse continuation`() {
        for (type in listOf(ErrorType.INVALID_REQUEST, ErrorType.AUTHENTICATION, ErrorType.PERMISSION)) {
            assertNull(
                controller.continuationForFailure(
                    ReanchorRound(previousBody(), failureWith(type = type), 0),
                ),
                "type $type must not continue",
            )
        }
    }

    @Test
    fun `the continuation budget caps at two`() {
        assertNotNull(controller.continuationForFailure(ReanchorRound(previousBody(), failureWith(), 1)))
        assertNull(controller.continuationForFailure(ReanchorRound(previousBody(), failureWith(), 2)))
    }

    @Test
    fun `envelope-only salvage continues - reasoning replay needs no prose`() {
        val partial = TurnOutcome.PartialRound(bodyText = "", reasoningEnvelopes = listOf("e1"))
        val next = controller.continuationForFailure(
            ReanchorRound(previousBody(), failureWith(partial = partial), 0),
        )
        assertNotNull(next)
        val input = next!!["input"]!!.jsonArray
        // user + decoded replay + marker; NO assistant-text item for the empty prose
        assertEquals(3, input.size)
    }

    @Test
    fun `an api_error failure is retryable too`() {
        val next = controller.continuationForFailure(
            ReanchorRound(previousBody(), failureWith(type = ErrorType.API_ERROR), 0),
        )
        assertNotNull(next)
    }

    @Test
    fun `a thinking-only partial restarts from scratch - nothing replayable, no marker`() {
        val partial = TurnOutcome.PartialRound(thinkingText = "deep partial reasoning already streamed")
        val next = controller.continuationForFailure(
            ReanchorRound(previousBody(), failureWith(partial = partial), 0),
        )
        // Verbatim whole-request retry (codex-rs responses_retry.rs parity) — NOT a marker
        // continuation: thinking cannot seed a resume, so the round re-runs clean.
        assertEquals(previousBody(), next)
    }

    @Test
    fun `an empty partial restarts from scratch - the whole-request retry`() {
        val next = controller.continuationForFailure(
            ReanchorRound(previousBody(), failureWith(partial = TurnOutcome.PartialRound()), 0),
        )
        assertEquals(previousBody(), next)
    }

    @Test
    fun `clean-slate restart respects the continuation budget`() {
        val empty = failureWith(partial = TurnOutcome.PartialRound())
        assertNotNull(controller.continuationForFailure(ReanchorRound(previousBody(), empty, 1)))
        assertNull(controller.continuationForFailure(ReanchorRound(previousBody(), empty, 2)))
    }

    @Test
    fun `clean-slate restart refuses a tool round - double-dispatch risk unchanged`() {
        val partial = TurnOutcome.PartialRound(hasToolUse = true)
        assertNull(
            controller.continuationForFailure(
                ReanchorRound(previousBody(), failureWith(partial = partial), 0),
            ),
        )
    }

    @Test
    fun `a failure without a partial refuses continuation`() {
        assertNull(
            controller.continuationForFailure(
                ReanchorRound(previousBody(), failureWith(partial = null), 0),
            ),
        )
    }
}

// ── translator salvage payload ──────────────────────────────────────────────────────────────

private class NullSink : WireSink {
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

private fun reanchorCtx(fired: WatchdogFired? = null, collect: Boolean = true) = StreamTurnContext(
    compact = false,
    emitEncryptedReasoning = EmitEncryptedReasoning(false),
    encodeReasoningEnvelope = { item ->
        item["id"]?.let { "env:$it" }?.takeIf { item["encrypted_content"] != null }
    },
    clientGone = { false },
    watchdogFired = { fired },
    streamIdleMsForMessage = 180_000,
    upstreamTimeoutMsForMessage = 900_000,
    // re-anchor salvage is asserted per round here; a fresh instance is the turn's state
    summaryPartsShared = SharedSummaryParts(),
    collectReasoningEnvelopes = collect,
)

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private fun toolAddedEv(): JsonObject = ev(
    """{"type":"response.output_item.added","output_index":0,""" +
        """"item":{"type":"function_call","call_id":"t1","name":"run"}}""",
)

private fun failedEv(): JsonObject = ev(
    """{"type":"response.failed","response":{"error":{"code":"server_error","message":"overloaded"}}}""",
)

private fun reasoningDoneEv(): JsonObject = ev(
    """{"type":"response.output_item.done","output_index":0,""" +
        """"item":{"type":"reasoning","id":"r1","encrypted_content":"blob"}}""",
)

class ResponsesReanchorPartialTest {

    @Test
    fun `a truncated stream after text carries the partial prose`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"The fix is to"}"""),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.OVERLOADED, failure.type)
        val partial = failure.partial
        assertNotNull(partial)
        assertEquals("The fix is to", partial!!.bodyText)
        assertTrue(partial.emittedText)
        assertFalse(partial.toolTearOpen)
        assertFalse(partial.hasToolUse)
    }

    @Test
    fun `a provider-reported failure event carries the partial too`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"half an ans"}"""),
                failedEv(),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.providerReported)
        assertEquals("half an ans", failure.partial?.bodyText)
    }

    @Test
    fun `a deterministic upstream rejection is never re-POSTed`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.failed","response":{"error":{""" +
                        """"code":"invalid_parameter","message":"request rejected by policy"}}}""",
                ),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, failure.type)
        assertTrue(failure.providerReported)
        assertNull(failure.partial)
        assertNull(controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0)))
    }

    // DR-10 redo (codex end-to-end counterexample): the arm above passed even pre-fix because its
    // message carries no transient wording. The SAME deterministic code whose message says
    // "unavailable ... try again" used to flip transient — free text overruled provenance and the
    // whole context re-POSTed. The code rides classify() separately now; wording buys no replay.
    @Test
    fun `a deterministic code with transient-sounding text still never re-POSTs - DR-10`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.failed","response":{"error":{"code":"invalid_parameter",""" +
                        """"message":"The selected model is unavailable in your region, try again"}}}""",
                ),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.providerReported)
        assertNull(failure.partial, "a deterministic failure must not carry a re-POSTable partial")
        assertNull(controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0)))
    }

    // DR-10 sweep follow-up: the FLAT error event has no error envelope — the reducer's payload
    // pick lands on the event itself, whose `type` is the SSE event discriminator ("error"), not
    // vendor provenance. Riding it into classify() as a structured code made every code-less flat
    // error deterministic, withholding the retry its own message wording grants.
    @Test
    fun `a flat error event's discriminator is not vendor provenance - DR-10`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"half an ans"}"""),
                ev("""{"type":"error","code":null,"message":"The server had an error, please try again"}"""),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.providerReported)
        assertEquals("half an ans", failure.partial?.bodyText, "transient flat error must keep its salvage")
        val continuation = controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0))
        assertNotNull(continuation, "a transient flat error must earn a re-POST")
        assertTrue(continuation.toString().contains("half an ans"), "the marker continuation carries the salvage")
    }

    @Test
    fun `a transient server failure remains eligible for a clean restart`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev(
                    """{"type":"response.failed","response":{"error":{""" +
                        """"code":"server_error","message":"temporarily unavailable"}}}""",
                ),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertNotNull(failure.partial)
        assertEquals(
            previousBody(),
            controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0)),
        )
    }

    @Test
    fun `a content-filtered turn is deterministic and is never re-POSTed`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"blocked"}"""),
                ev(
                    """{"type":"response.incomplete","response":{"status":"incomplete",""" +
                        """"incomplete_details":{"reason":"content_filter"}}}""",
                ),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals(ErrorType.API_ERROR, failure.type)
        assertNull(failure.partial)
        assertNull(controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0)))
    }

    @Test
    fun `a tool block torn mid-args marks the poison tear`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                toolAddedEv(),
                ev("""{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"x\":"}"""),
            ).asFlow(),
            NullSink(),
        )
        val partial = (outcome as TurnOutcome.Failure).partial
        assertNotNull(partial)
        assertTrue(partial!!.toolTearOpen)
    }

    @Test
    fun `a cleanly-closed tool is committed, not a tear`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                toolAddedEv(),
                ev("""{"type":"response.function_call_arguments.done","output_index":0,"arguments":"{\"x\":1}"}"""),
            ).asFlow(),
            NullSink(),
        )
        val partial = (outcome as TurnOutcome.Failure).partial
        assertNotNull(partial)
        assertFalse(partial!!.toolTearOpen)
        assertTrue(partial.hasToolUse)
    }

    @Test
    fun `a failed event's usage block is harvested into the partial`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"some text"}"""),
                ev(
                    """{"type":"response.failed","response":{""" +
                        """"error":{"code":"server_error","message":"overloaded"},""" +
                        """"usage":{"input_tokens":100,"output_tokens":42,""" +
                        """"output_tokens_details":{"reasoning_tokens":7}}}}""",
                ),
            ).asFlow(),
            NullSink(),
        )
        val partial = (outcome as TurnOutcome.Failure).partial
        assertEquals(42, partial?.usage?.outputTokens)
    }

    // DR-7 REVERSES the arm that stood here ("a watchdog fire carries no partial - its cancellation
    // owns the turn"). That sentence described a watchdog that cancelled the whole TURN; it now
    // reaps a single ROUND, so an idle fire is a stall report about a round that still has real
    // salvage — and withholding it threw away text the client had already been shown.
    @Test
    fun `an idle watchdog fire carries its partial - the round is reaped, not the turn`() = runTest {
        val outcome = ResponsesStreamTranslator(
            reanchorCtx(fired = WatchdogFired.Idle(idleMs = 1, sawFirstByte = true)),
        ).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"text"}"""),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertEquals("text", failure.partial?.bodyText, "an idle stall must keep what the round produced")
        assertNotNull(
            controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0)),
            "and that salvage must be enough to earn the continuation",
        )
    }

    // The half that did NOT reverse, and the reason the split is not cosmetic: totalCap is the
    // whole-turn wall. Handing it a partial would let the turn continue past the one budget that
    // exists to stop it, which is the opposite of what its own name says.
    @Test
    fun `a totalCap watchdog fire carries no partial - the whole-turn wall is not continuable`() = runTest {
        val outcome = ResponsesStreamTranslator(
            reanchorCtx(fired = WatchdogFired.TotalCap(elapsedMs = 1)),
        ).driveTurn(
            listOf(
                ev("""{"type":"response.output_text.delta","output_index":0,"delta":"text"}"""),
            ).asFlow(),
            NullSink(),
        )
        val failure = outcome as TurnOutcome.Failure
        assertNull(failure.partial, "the whole-turn cap must not hand out salvage")
        assertNull(
            controller.continuationForFailure(ReanchorRound(previousBody(), failure, attempt = 0)),
            "and with no salvage there is nothing to continue from",
        )
    }

    @Test
    fun `reasoning envelopes ride the failed round for replay`() = runTest {
        val outcome = ResponsesStreamTranslator(reanchorCtx()).driveTurn(
            listOf(
                ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}"""),
                reasoningDoneEv(),
            ).asFlow(),
            NullSink(),
        )
        val partial = (outcome as TurnOutcome.Failure).partial
        assertEquals(listOf("env:\"r1\""), partial?.reasoningEnvelopes)
    }
}
