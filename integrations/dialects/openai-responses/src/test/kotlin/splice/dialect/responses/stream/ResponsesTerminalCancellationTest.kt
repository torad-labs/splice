package splice.dialect.responses.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.PersistedCause
import splice.core.turn.TurnOutcome
import splice.upstream.retry.RetryEngine
import splice.upstream.retry.RetryLedger
import splice.upstream.retry.RetryStep
import splice.upstream.retry.TurnFacts
import splice.upstream.sse.WireSink
import java.io.IOException
import java.time.Instant

private val CONTENT_START = Json.parseToJsonElement(
    """{"type":"response.output_item.added","output_index":0,"item":{"type":"message","role":"assistant"}}""",
).jsonObject
private val CONTENT_DELTA = Json.parseToJsonElement(
    """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"synthetic content"}""",
).jsonObject
private val TERMINAL_WITH_USAGE = Json.parseToJsonElement(
    """{"type":"response.completed","response":{"status":"completed","usage":{
        "input_tokens":100,"output_tokens":7,"input_tokens_details":{"cached_tokens":40}},"output":[]}}""",
).jsonObject

class ResponsesTerminalCancellationTest {
    @Test
    fun `a completed response returns its content and usage while the upstream body stays open`() = runTest {
        val events = flow {
            emit(CONTENT_START)
            emit(CONTENT_DELTA)
            emit(TERMINAL_WITH_USAGE)
            awaitCancellation()
        }
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(events, RecordingSink()) as TurnOutcome.Success
        assertEquals("synthetic content", outcome.text.bodyText)
        assertEquals(100L, outcome.usage.inputTokens)
        assertEquals(7L, outcome.usage.outputTokens)
    }

    @Test
    fun `cancellation after a parsed response terminal cannot discard its reported usage`() = runTest {
        val cancellation = CancellationException("synthetic post-terminal cancellation")
        val events = flow {
            emit(CONTENT_START)
            emit(CONTENT_DELTA)
            emit(TERMINAL_WITH_USAGE)
        }.onCompletion {
            throw cancellation
        }
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(events, RecordingSink()) as TurnOutcome.Success
        assertEquals(100L, outcome.usage.inputTokens)
        assertEquals(7L, outcome.usage.outputTokens)
        assertEquals(40L, outcome.usage.cachedTokens)
    }

    @Test
    fun `a cancelled raw reader still hands its parsed terminal to accounting after sink cleanup`() = runTest {
        val known = CompletableDeferred<TurnOutcome>()
        val recording = RecordingSink()
        var closes = 0
        val sink = object : WireSink by recording {
            override suspend fun closeAll() {
                currentCoroutineContext().ensureActive()
                closes++
                recording.closeAll()
            }
        }
        val reader = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val events = flow {
                try {
                    emit(CONTENT_START)
                    emit(CONTENT_DELTA)
                    emit(TERMINAL_WITH_USAGE)
                } finally {
                    currentCoroutineContext().cancel()
                }
            }
            known.complete(ResponsesStreamTranslator(ctx()).driveTurn(events, sink))
        }
        reader.join()
        assertTrue(reader.isCancelled, "the reader's real cancellation is not revoked")
        assertTrue(known.isCompleted, "the parsed terminal must reach raw accounting before the reader unwinds")
        val outcome = known.await() as TurnOutcome.Success
        assertEquals(100L, outcome.usage.inputTokens)
        assertEquals(7L, outcome.usage.outputTokens)
        assertEquals(1, closes, "completed-round cleanup runs once in the non-cancelled cleanup context")
    }

    @Test
    fun `failed and filtered terminals retain billing without enabling retry salvage after cancellation`() = runTest {
        val terminals = listOf(
            """{"type":"response.failed","response":{"status":"failed","error":{"code":"invalid_prompt",
                "message":"synthetic refusal"},"usage":{"input_tokens":100,"output_tokens":7}}}""",
            """{"type":"response.incomplete","response":{"status":"incomplete",
                "incomplete_details":{"reason":"content_filter"},"usage":{"input_tokens":100,"output_tokens":7}}}""",
        )
        for (terminal in terminals) {
            val events = flow {
                emit(Json.parseToJsonElement(terminal).jsonObject)
            }.onCompletion {
                throw CancellationException("synthetic failed-terminal cancellation")
            }
            val outcome = ResponsesStreamTranslator(ctx()).driveTurn(events, RecordingSink()) as TurnOutcome.Failure
            // The bill the turn reads (RoundSplice.withFailureSalvage): the round's salvage, else what was salvaged.
            val billed = outcome.partial?.usage ?: outcome.salvagedUsage
            assertEquals(100L, billed.inputTokens)
            assertEquals(7L, billed.outputTokens)
            assertTrue(outcome.cause.permanent)
            // Failure-handling slice 4: the salvage rides every in-band failure, and the engine ends a permanent one
            // even where the head could continue from it, so billing never turns it into a re-anchor.
            val facts = TurnFacts(false, false, 0L, false, true, Long.MAX_VALUE, null)
            val step = RetryEngine.next(outcome.cause, outcome.at, RetryLedger(), facts, Instant.EPOCH)
            assertTrue(step is RetryStep.End, "billing must not turn a permanent ending into a re-anchor: $step")
        }
    }

    @Test
    fun `a completed terminal still carries its bill when sink cleanup throws`() = runTest {
        val sink = object : WireSink by RecordingSink() {
            override suspend fun closeAll() {
                throw IOException("synthetic completed-round cleanup failure")
            }
        }
        val events = flow { emit(TERMINAL_WITH_USAGE) }
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(events, sink) as TurnOutcome.Failure
        assertEquals(100L, outcome.salvagedUsage.inputTokens)
        assertEquals(7L, outcome.salvagedUsage.outputTokens)
        assertNull(outcome.partial, "a cleanup fault never regenerates already completed source")
        assertEquals(PersistedCause.INTERNAL, outcome.cause.persisted)
        assertTrue(
            outcome.evidence.message.contains("response cleanup failed"),
            "cleanup failure stays visible on the outcome",
        )
    }

    @Test
    fun `a cleanup failure keeps a transient terminal bill but cannot re-anchor its incomplete cleanup`() = runTest {
        val sink = object : WireSink by RecordingSink() {
            override suspend fun closeAll() {
                throw IOException("synthetic failed-terminal cleanup")
            }
        }
        val events = flow {
            emit(
                Json.parseToJsonElement(
                    """{"type":"response.failed","response":{"error":{"code":"server_error","message":"synthetic"},
                        "usage":{"input_tokens":100,"output_tokens":7}}}""",
                ).jsonObject,
            )
        }
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(events, sink) as TurnOutcome.Failure
        assertNull(outcome.partial, "cleanup failure must not re-anchor already issued source")
        assertEquals(100L, outcome.salvagedUsage.inputTokens)
        assertEquals(7L, outcome.salvagedUsage.outputTokens)
    }

    @Test
    fun `cleanup cancellation cannot bypass validation of unswept malformed tool arguments`() = runTest {
        val sink = object : WireSink by RecordingSink() {
            override suspend fun closeBlock(index: splice.core.index.WireBlockIndex) {
                throw CancellationException("synthetic cleanup cancellation")
            }
        }
        val events = flow {
            emit(
                Json.parseToJsonElement(
                    """{"type":"response.output_item.added","output_index":0,
                        "item":{"type":"function_call","call_id":"synthetic-call","name":"synthetic-tool"}}""",
                ).jsonObject,
            )
            emit(
                Json.parseToJsonElement(
                    """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{"}""",
                ).jsonObject,
            )
            emit(
                Json.parseToJsonElement(
                    """{"type":"response.output_item.added","output_index":1,"item":{
                        "type":"custom_tool_call","id":"synthetic-exec","call_id":"synthetic-exec","name":"exec"}}""",
                ).jsonObject,
            )
            emit(
                Json.parseToJsonElement(
                    """{"type":"response.custom_tool_call_input.delta","output_index":1,"delta":"return 1;"}""",
                ).jsonObject,
            )
            emit(TERMINAL_WITH_USAGE)
        }
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(events, sink) as TurnOutcome.Failure
        assertEquals(PersistedCause.TOOL_TEAR, outcome.cause.persisted)
        assertEquals(100L, outcome.salvagedUsage.inputTokens)
    }

    @Test
    fun `cancellation before any response terminal still propagates and invents no usage`() = runTest {
        val cancellation = CancellationException("synthetic pre-terminal cancellation")
        val events = flow<kotlinx.serialization.json.JsonObject> { throw cancellation }
        val thrown = try {
            ResponsesStreamTranslator(ctx()).driveTurn(events, RecordingSink())
            null
        } catch (error: CancellationException) {
            error
        }
        assertSame(cancellation, thrown)
    }
}
