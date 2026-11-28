package splice.head.turn

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.failure.FailureCause
import splice.core.turn.ResponseShape
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.dialect.responses.responsesTestReanchor
import splice.head.compact.CompactStats
import splice.head.pipeline.TurnPipeline
import splice.head.turn.CodeModeSourceRefusalFixtures.REFUSAL_WORDS
import splice.head.turn.CodeModeSourceRefusalFixtures.ROUND_INPUT_TOKENS
import splice.head.turn.CodeModeSourceRefusalFixtures.ROUND_OUTPUT_TOKENS
import splice.head.turn.CodeModeSourceRefusalFixtures.SECOND_INPUT_TOKENS
import splice.head.turn.CodeModeSourceRefusalFixtures.SECOND_OUTPUT_TOKENS
import splice.head.turn.CodeModeSourceRefusalFixtures.bridge
import splice.head.turn.CodeModeSourceRefusalFixtures.completedExecRound
import splice.head.turn.CodeModeSourceRefusalFixtures.earlyWriteTear
import splice.head.turn.CodeModeSourceRefusalFixtures.emitter
import splice.head.turn.CodeModeSourceRefusalFixtures.meta
import splice.head.turn.CodeModeSourceRefusalFixtures.refusedSource
import splice.head.turn.CodeModeSourceRefusalFixtures.requestBody
import splice.head.turn.CodeModeSourceRefusalFixtures.strategy
import splice.head.turn.CodeModeSourceRefusalFixtures.transientSource
import splice.head.turn.CodeModeSourceRefusalFixtures.unfinishedExecEof
import java.nio.file.Path

class CodeModeSourceRefusalWireTest {
    @Test
    fun `content-filtered native code-mode exec source after content emits invalid_request_error in band`(
        @TempDir tempDir: Path,
    ) = runTest {
        val frames = mutableListOf<String>()
        val emitter = emitter(write = { frames += it })
        val pipeline = TurnPipeline(CompactStats(tempDir.resolve("compact.jsonl")), {}, { it })
        val bridge = bridge(tempDir)
        try {
            val strategy = strategy(
                bridge,
                emitter,
                finish = { pipeline.finishStream(emitter, it, meta(), 1L) },
            ) { sink -> refusedSource(sink) }
            strategy.run(requestBody(), null, responsesTestReanchor())
            val errorFrame = frames.single { it.startsWith("event: error\n") }
            val data = errorFrame.substringAfter("data: ").trim()
            val error = Json.parseToJsonElement(data).jsonObject.getValue("error").jsonObject
            assertEquals("invalid_request_error", error.getValue("type").jsonPrimitive.content)
            val message = error.getValue("message").jsonPrimitive.content
            assertTrue(message.contains(REFUSAL_WORDS), message)
            assertFalse(message.contains("runtime start was interrupted"), message)
            assertTrue(frames.none { it.startsWith("event: message_delta") || it.startsWith("event: message_stop") })
        } finally {
            bridge.onHeadStop()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["server-error", "eof-outcome", "eof-io"])
    fun `retryable error or transport tear after prose and unfinished hidden exec recovers via reanchor`(
        kind: String,
        @TempDir tempDir: Path,
    ) = runTest {
        val frames = mutableListOf<String>()
        val emitter = emitter(write = { frames += it })
        val bridge = bridge(tempDir)
        var finished: TurnOutcome? = null
        var first = true
        try {
            val strategy = strategy(bridge, emitter, finish = { finished = it }) { sink ->
                if (first) {
                    first = false
                    transientSource(sink, kind)
                } else {
                    sink.addTextBlock(" Done.")
                    TurnOutcome.Success(
                        hasToolUse = false,
                        incomplete = false,
                        usage = Usage(inputTokens = ROUND_INPUT_TOKENS, outputTokens = SECOND_OUTPUT_TOKENS),
                        text = RoundText(bodyText = " Done.", emittedText = true),
                        shape = ResponseShape(messageClosed = true),
                    )
                }
            }
            strategy.run(requestBody(), null, responsesTestReanchor())
            val success = finished as TurnOutcome.Success
            assertEquals("Starting work. Done.", success.text.bodyText)
            assertTrue(frames.any { it.contains("Starting work.") })
            assertTrue(frames.any { it.contains(" Done.") })
            assertTrue(frames.none { it.startsWith("event: error\n") })
        } finally {
            bridge.onHeadStop()
        }
    }

    @Test
    fun `permanent refusal on a second hidden exec round bills both rounds without undercount`(
        @TempDir tempDir: Path,
    ) = runTest {
        val emitter = emitter(contentReached = false)
        val bridge = bridge(tempDir, completing = true)
        var finished: TurnOutcome? = null
        var round = 0
        try {
            val strategy = strategy(bridge, emitter, finish = { finished = it }) { sink ->
                if (round++ == 0) {
                    completedExecRound(sink)
                } else {
                    refusedSource(
                        sink,
                        partial = TurnOutcome.PartialRound(
                            usage = Usage(inputTokens = SECOND_INPUT_TOKENS, outputTokens = SECOND_OUTPUT_TOKENS),
                        ),
                    )
                }
            }
            strategy.run(requestBody(), null, responsesTestReanchor())
            val failure = finished as TurnOutcome.Failure
            assertTrue(failure.cause is FailureCause.Provider.Refused)
            assertTrue(failure.cause.permanent)
            assertNull(failure.partial)
            assertEquals(SECOND_INPUT_TOKENS, failure.salvagedUsage.inputTokens)
            assertEquals(ROUND_OUTPUT_TOKENS + SECOND_OUTPUT_TOKENS, failure.salvagedUsage.outputTokens)
            assertEquals(1L, failure.salvagedUsage.absorbed.rounds)
            assertEquals(ROUND_INPUT_TOKENS, failure.salvagedUsage.absorbed.inputTokens)
            assertEquals(ROUND_OUTPUT_TOKENS, failure.salvagedUsage.absorbed.outputTokens)
        } finally {
            bridge.onHeadStop()
        }
    }

    @Test
    fun `retryable EOF after completed hidden exec with no prose does not resend and preserves billing`(
        @TempDir tempDir: Path,
    ) = runTest {
        val emitter = emitter(contentReached = false)
        val bridge = bridge(tempDir, completing = true)
        var finished: TurnOutcome? = null
        var round = 0
        try {
            val strategy = strategy(bridge, emitter, finish = { finished = it }) { sink ->
                when (round++) {
                    0 -> completedExecRound(sink)
                    1 -> unfinishedExecEof(sink)
                    else -> TurnOutcome.Success(
                        hasToolUse = false,
                        incomplete = false,
                        usage = Usage(),
                        text = RoundText(bodyText = "unexpected resend", emittedText = true),
                    )
                }
            }
            strategy.run(requestBody(), null, responsesTestReanchor())
            val failure = finished as TurnOutcome.Failure
            assertTrue(failure.cause is FailureCause.Path.Torn)
            assertNull(failure.partial)
            assertEquals(1L, failure.salvagedUsage.absorbed.rounds)
            assertEquals(ROUND_INPUT_TOKENS, failure.salvagedUsage.absorbed.inputTokens)
            assertEquals(ROUND_OUTPUT_TOKENS, failure.salvagedUsage.absorbed.outputTokens)
        } finally {
            bridge.onHeadStop()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `retryable IO tear after visible tool call and unfinished hidden exec never reanchors`(
        toolOpen: Boolean,
        @TempDir tempDir: Path,
    ) = runTest {
        val frames = mutableListOf<String>()
        val emitter = emitter(write = { frames += it })
        val bridge = bridge(tempDir)
        var finished: TurnOutcome? = null
        var first = true
        try {
            val strategy = strategy(bridge, emitter, finish = { finished = it }) { sink ->
                if (first) {
                    first = false
                    sink.addTextBlock("Starting work.")
                    val tool = sink.openTool("toolu_1", "Read")
                    sink.inputJsonDelta(tool, """{"file_path":"a.txt"}""")
                    if (!toolOpen) sink.closeBlock(tool)
                    unfinishedExecEof(sink)
                } else {
                    sink.addTextBlock("unexpected continuation")
                    TurnOutcome.Success(
                        hasToolUse = false,
                        incomplete = false,
                        usage = Usage(),
                        text = RoundText(bodyText = "unexpected continuation", emittedText = true),
                    )
                }
            }
            strategy.run(requestBody(), null, responsesTestReanchor())
            assertTrue(frames.none { it.contains("unexpected continuation") })
            if (toolOpen) {
                val failure = finished as TurnOutcome.Failure
                assertEquals(true, (failure.cause as? FailureCause.Path.Torn)?.toolOpen)
                assertEquals(true, failure.partial?.hasToolUse)
                assertEquals(true, failure.partial?.toolTearOpen)
            } else {
                val salvaged = finished as TurnOutcome.Success
                assertTrue(salvaged.hasToolUse)
                assertEquals("Starting work.", salvaged.text.bodyText)
            }
        } finally {
            bridge.onHeadStop()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["open-eof-outcome", "refused-eof-io", "refused-eof-outcome"])
    fun `early Write opened or refused before a torn unfinished exec never reanchors or salvages as tool_use`(
        kind: String,
        @TempDir tempDir: Path,
    ) = runTest {
        val frames = mutableListOf<String>()
        val emitter = emitter(write = { frames += it })
        val bridge = bridge(tempDir)
        var finished: TurnOutcome? = null
        var first = true
        try {
            val strategy = strategy(
                bridge,
                emitter,
                tools = setOf("Read", "Write"),
                finish = { finished = it },
            ) { sink ->
                if (first) {
                    first = false
                    earlyWriteTear(sink, kind)
                } else {
                    sink.addTextBlock("unexpected continuation")
                    TurnOutcome.Success(
                        hasToolUse = false,
                        incomplete = false,
                        usage = Usage(),
                        text = RoundText(bodyText = "unexpected continuation", emittedText = true),
                    )
                }
            }
            strategy.run(requestBody(), null, responsesTestReanchor())
            val failure = finished as TurnOutcome.Failure
            assertTrue(frames.none { it.contains("unexpected continuation") })
            assertEquals(true, (failure.cause as? FailureCause.Path.Torn)?.toolOpen)
            assertEquals(true, failure.partial?.hasToolUse)
            assertEquals(true, failure.partial?.toolTearOpen)
            if (kind.startsWith("refused-")) assertTrue(frames.any { it.contains("splice_not_written") })
        } finally {
            bridge.onHeadStop()
        }
    }
}
