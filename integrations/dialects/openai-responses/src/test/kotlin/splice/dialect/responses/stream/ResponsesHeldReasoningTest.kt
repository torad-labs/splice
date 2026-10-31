package splice.dialect.responses.stream

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.TurnOutcome
import splice.upstream.transport.BufferCapacity

class ResponsesHeldReasoningTest {
    @Test
    fun `unfinished whitespace from distinct indexes shares the pending character cap`() = runTest {
        val sink = RecordingSink()
        val prefix = "\u2003".repeat(BufferCapacity.MAX_BUFFERED_CHARS / 2)
        val outcome = drive(
            listOf(raw(0, prefix), raw(1, prefix), answer(), completed()),
            sink,
        )

        assertTrue(outcome is TurnOutcome.Failure, "aggregate held prefixes must trip the stream guard")
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.evidence.message.contains("exceeded max buffered size"), failure.evidence.message)
        assertFalse(failure.cause.providerReported, "local capacity rejection is not a provider failure")
        assertFalse(sink.calls.any { it.startsWith("text#") }, "the next event cannot bypass a full pending buffer")
        assertNoReasoningRows(sink)
    }

    @Test
    fun `multibyte whitespace below the aggregate character cap is not charged as UTF8 bytes`() = runTest {
        val sink = RecordingSink()
        // Each EM SPACE occupies one UTF-16 char and three UTF-8 bytes. The shared cap uses chars.
        val prefix = "\u2003".repeat(BufferCapacity.MAX_BUFFERED_CHARS / 2 - 1)
        val outcome = drive(listOf(raw(0, prefix), raw(1, prefix), answer(), completed()), sink)

        assertTrue(outcome is TurnOutcome.Success, "under-cap held chars must allow the answer: $outcome")
        assertEquals(listOf("answer"), textDeltas(sink))
        assertNoReasoningRows(sink)
    }

    @ParameterizedTest
    @ValueSource(strings = ["completed", "failed", "eof"])
    fun `literal raw prefix survives a sparse ending without item completion`(ending: String) = runTest {
        val sink = RecordingSink()
        val events = listOf(raw(0, "<"), answer()) + terminalEvents(ending)
        val outcome = drive(events, sink)

        assertEquals(listOf("<"), thinkingDeltas(sink), "the model's literal prefix must reach the client once")
        assertEquals(1, sink.calls.count { it.startsWith("openThinking#") }, "one nonempty reasoning row")
        assertFalse(sink.calls.any { it.startsWith("redacted:") }, "no opaque substitute for readable text")
        assertEquals(listOf("answer"), textDeltas(sink))
        assertEquals(
            listOf("<", "answer"),
            sink.calls.filter { it.startsWith("think#") || it.startsWith("text#") }
                .map { it.substringAfter(':') },
            "held reasoning must precede the later visible answer, not move across it at termination",
        )
        assertEquals(ending == "completed", outcome is TurnOutcome.Success, "ending honesty must survive settlement")
        if (outcome is TurnOutcome.Success) assertEquals("<", outcome.text.thinkingText)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " \n\u2003", "<!-- -->"])
    fun `sparse termination does not turn empty raw prefixes into reasoning rows`(prefix: String) = runTest {
        val sink = RecordingSink()
        val outcome = drive(listOf(raw(0, prefix), answer(), completed()), sink)

        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(listOf("answer"), textDeltas(sink))
        assertNoReasoningRows(sink)
    }

    @Test
    fun `item completion and response completion do not deliver a literal prefix twice`() = runTest {
        val sink = RecordingSink()
        val itemDone = event(
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"r"}}""",
        )
        val outcome = drive(listOf(raw(0, "<"), itemDone, answer(), completed()), sink)

        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(listOf("<"), thinkingDeltas(sink))
        assertEquals(1, sink.calls.count { it.startsWith("openThinking#") })
        assertFalse(sink.calls.any { it.startsWith("redacted:") })
    }

    private suspend fun drive(events: List<JsonObject>, sink: RecordingSink): TurnOutcome =
        ResponsesStreamTranslator(ctx(emit = true)).driveTurn(events.asFlow(), sink)

    private fun assertNoReasoningRows(sink: RecordingSink) {
        assertFalse(sink.calls.any { it.startsWith("openThinking#") }, "no empty thinking rows")
        assertTrue(thinkingDeltas(sink).isEmpty())
        assertFalse(sink.calls.any { it.startsWith("redacted:") }, "no opaque reasoning rows")
    }

    private fun thinkingDeltas(sink: RecordingSink): List<String> = sink.calls
        .filter { it.startsWith("think#") }.map { it.substringAfter(':') }

    private fun textDeltas(sink: RecordingSink): List<String> = sink.calls
        .filter { it.startsWith("text#") }.map { it.substringAfter(':') }

    private fun raw(index: Int, text: String): JsonObject = buildJsonObject {
        put("type", "response.reasoning_text.delta")
        put("output_index", index)
        put("delta", text)
    }

    private fun answer(): JsonObject = event(
        """{"type":"response.output_text.delta","output_index":2,"delta":"answer"}""",
    )

    private fun completed(): JsonObject = event(
        """{"type":"response.completed","response":{"status":"completed","output":[]}}""",
    )

    private fun terminalEvents(kind: String): List<JsonObject> = when (kind) {
        "completed" -> listOf(completed())
        "failed" -> listOf(
            event(
                """{"type":"response.failed","response":{"error":{"code":"server_error",""" +
                    """"message":"fixture failure"}}}""",
            ),
        )
        "eof" -> emptyList()
        else -> error("unknown fixture ending")
    }

    private fun event(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
