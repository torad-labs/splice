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

class ResponsesHeldSummaryTest {
    private val answer = delta("output", 2, "answer")
    private val completed = event(
        """{"type":"response.completed","response":{"status":"completed","output":[]}}""",
    )

    @Test
    fun `one ambiguous summary cannot accumulate beyond the shared character budget`() = runTest {
        val sink = RecordingSink()
        val spaces = "\u2003".repeat(BufferCapacity.MAX_BUFFERED_CHARS / 2)
        val outcome = drive(
            listOf(
                delta("reasoning_summary", 0, "<"),
                delta("reasoning_summary", 0, spaces),
                delta("reasoning_summary", 0, spaces),
                answer,
                completed,
            ),
            sink,
        )

        assertTrue(outcome is TurnOutcome.Failure, "held summary chars must trip the shared capacity guard")
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.evidence.message.contains("exceeded max buffered size"), failure.evidence.message)
        assertFalse(failure.cause.providerReported)
        assertFalse(sink.calls.any { it.startsWith("text#") }, "a full summary budget cannot admit the next answer")
        assertFalse(sink.calls.any { it.startsWith("redacted:") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["raw", "text", "thinking"])
    fun `held summary shares one character budget with other reasoning and visible buffers`(surface: String) = runTest {
        val half = BufferCapacity.MAX_BUFFERED_CHARS / 2
        val other = when (surface) {
            "raw" -> delta("reasoning", 0, "\u2003".repeat(half))
            "text" -> delta("output", 0, "A".repeat(half))
            "thinking" -> delta("reasoning", 0, "A".repeat(half))
            else -> error("unknown buffer fixture")
        }
        val sink = RecordingSink()
        val outcome = drive(
            listOf(other, delta("reasoning_summary", 1, "<" + "\u2003".repeat(half - 1)), answer, completed),
            sink,
        )

        assertTrue(outcome is TurnOutcome.Failure, "the shared sum reaches the cap although each surface is below it")
        val failure = outcome as TurnOutcome.Failure
        assertTrue(failure.evidence.message.contains("exceeded max buffered size"), failure.evidence.message)
        assertFalse(failure.cause.providerReported)
        assertFalse(visible(sink).contains("answer"), "a later event cannot bypass the aggregate character budget")
        assertFalse(sink.calls.any { it.startsWith("redacted:") })
    }

    @Test
    fun `multibyte summary whitespace below the character limit remains valid`() = runTest {
        val sink = RecordingSink()
        // EM SPACE is one UTF-16 char and three UTF-8 bytes; this prefix is under the former cap only.
        val text = "<" + "\u2003".repeat(BufferCapacity.MAX_BUFFERED_CHARS / 2)
        val done = buildJsonObject {
            put("type", "response.reasoning_summary_text.done")
            put("output_index", 0)
            put("text", text)
        }
        val outcome = drive(listOf(delta("reasoning_summary", 0, text), done, answer, completed), sink)

        assertTrue(outcome is TurnOutcome.Success, "capacity is measured in chars, not encoded UTF-8 bytes")
        assertEquals(listOf(text, "answer"), visible(sink))
        assertEquals(1, sink.calls.count { it.startsWith("openThinking#") })
        assertFalse(sink.calls.any { it.startsWith("redacted:") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["completed", "failed", "eof"])
    fun `literal summary prefix precedes later prose without summary or item completion`(ending: String) = runTest {
        val sink = RecordingSink()
        val outcome = drive(listOf(delta("reasoning_summary", 0, "<"), answer) + terminalEvents(ending), sink)

        assertEquals(listOf("<", "answer"), visible(sink), "summary settlement cannot invert the visible boundary")
        assertEquals(1, sink.calls.count { it.startsWith("openThinking#") }, "exactly one nonempty reasoning row")
        assertFalse(sink.calls.any { it.startsWith("redacted:") })
        assertEquals(ending == "completed", outcome is TurnOutcome.Success, "settlement cannot fabricate success")
    }

    @ParameterizedTest
    @ValueSource(strings = ["completed", "failed", "eof"])
    fun `literal summary prefix survives an ending with no subsequent visible phase`(ending: String) = runTest {
        val sink = RecordingSink()
        val outcome = drive(listOf(delta("reasoning_summary", 0, "<")) + terminalEvents(ending), sink)

        assertEquals(listOf("<"), visible(sink), "terminal and EOF fallback must settle an unfinished summary")
        assertEquals(1, sink.calls.count { it.startsWith("openThinking#") })
        assertFalse(sink.calls.any { it.startsWith("redacted:") })
        assertEquals(ending == "completed", outcome is TurnOutcome.Success)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " \t\n\u2003", "<!-- -->", " \u2003<!-- -->\n"])
    fun `blank and exact comment summaries create no rows across generated chunk splits`(text: String) = runTest {
        for (split in 0..text.length) {
            val sink = RecordingSink()
            val chunks = listOf(text.substring(0, split), text.substring(split))
                .map { delta("reasoning_summary", 0, it) }
            val outcome = drive(chunks + answer + completed, sink)

            assertTrue(outcome is TurnOutcome.Success)
            assertEquals(listOf("answer"), visible(sink), "empty summary changed at split $split")
            assertFalse(sink.calls.any { it.startsWith("openThinking#") }, "no empty Thinking row at split $split")
            assertFalse(sink.calls.any { it.startsWith("redacted:") })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [" \u2003<", " \u2003literal 😀 e\u0301", "<!-- real body -->"])
    fun `raw prefix classification preserves every char across generated chunk splits`(text: String) = runTest {
        val itemDone = event(
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"raw"}}""",
        )
        for (split in 0..text.length) {
            val sink = RecordingSink()
            val chunks = listOf(text.substring(0, split), text.substring(split)).map { delta("reasoning", 0, it) }
            val outcome = drive(chunks + itemDone + answer + completed, sink)
            val reasoning = sink.calls.filter { it.startsWith("think#") }.joinToString("") { it.substringAfter(':') }

            assertTrue(outcome is TurnOutcome.Success)
            assertEquals(text, reasoning, "raw prefix changed at split $split")
            assertEquals(1, sink.calls.count { it.startsWith("openThinking#") })
            assertFalse(sink.calls.any { it.startsWith("redacted:") })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["reasoning", "reasoning_summary"])
    fun `small whitespace chunks retain exact bytes when a literal finally completes`(channel: String) = runTest {
        val chunks = List(128) { "\u2003 \t" } + "<"
        val text = chunks.joinToString("")
        val done = if (channel == "reasoning_summary") {
            buildJsonObject {
                put("type", "response.reasoning_summary_text.done")
                put("output_index", 0)
                put("text", text)
            }
        } else {
            event(
                """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"raw"}}""",
            )
        }
        val sink = RecordingSink()
        val outcome = drive(chunks.map { delta(channel, 0, it) } + done + answer + completed, sink)
        val reasoning = sink.calls.filter { it.startsWith("think#") }.joinToString("") { it.substringAfter(':') }

        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(text, reasoning)
        assertEquals(1, sink.calls.count { it.startsWith("openThinking#") })
        assertFalse(sink.calls.any { it.startsWith("redacted:") })
    }

    @Test
    fun `settled summary and repeated header keep their exact once per round policy`() = runTest {
        val header = "**Inspecting**"
        val sink = RecordingSink()
        val events = (0..1).flatMap { index ->
            listOf(
                delta("reasoning_summary", index, header),
                buildJsonObject {
                    put("type", "response.reasoning_summary_text.done")
                    put("output_index", index)
                    put("text", header)
                },
            )
        }
        val outcome = drive(events + answer + completed, sink)

        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(listOf(header, "answer"), visible(sink))
        assertEquals(1, sink.calls.count { it.startsWith("openThinking#") })
        assertFalse(sink.calls.any { it.startsWith("redacted:") })
    }

    private suspend fun drive(events: List<JsonObject>, sink: RecordingSink): TurnOutcome =
        ResponsesStreamTranslator(ctx(emit = true)).driveTurn(events.asFlow(), sink)

    private fun delta(channel: String, index: Int, text: String): JsonObject = buildJsonObject {
        put("type", "response.${channel}_text.delta")
        put("output_index", index)
        put("delta", text)
    }

    private fun visible(sink: RecordingSink): List<String> = sink.calls
        .filter { it.startsWith("think#") || it.startsWith("text#") }.map { it.substringAfter(':') }

    private fun terminalEvents(kind: String): List<JsonObject> = when (kind) {
        "completed" -> listOf(completed)
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
