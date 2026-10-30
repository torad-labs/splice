// NEW: V4-450 — header-only reasoning-summary parts render once per round per distinct text.
package splice.dialect.responses.stream

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.upstream.transport.BufferCapacity

// The nine headers of one gpt-6-sol round in the Oct 1 trace, and the order its 63 reasoning items
// carried them in (one header-only summary part per item), as indices into the list.
private val HEADERS = listOf(
    "**Reading code and logs**",
    "**Reading code and log**",
    "**Reviewing targeted code and log**",
    "**Reviewing code and log**",
    "**Checking code and log**",
    "**Reading targeted code and log**",
    "**Reviewing ledger code and log**",
    "**Reading targeted code/log**",
    "**Checking targeted code and log**",
)
private const val ROUND_ORDER = "012311111111415315111353161111117531111318111111135111111353111"

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private fun event(type: String, oi: Int, extra: Map<String, String> = emptyMap()): JsonObject = buildJsonObject {
    put("type", type)
    put("output_index", oi)
    extra.forEach { (k, v) -> put(k, v) }
}

private val completed = ev(
    """{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":1,"output_tokens":1}}}""",
)

/** One reasoning item as the backend streams it live: one summary part, its deltas, its done. */
private fun liveItem(oi: Int, deltas: List<String>, summaryOnDone: String? = null): List<JsonObject> = buildList {
    add(ev("""{"type":"response.output_item.added","output_index":$oi,"item":{"type":"reasoning","id":"rs_$oi"}}"""))
    add(event("response.reasoning_summary_part.added", oi))
    deltas.forEach { add(event("response.reasoning_summary_text.delta", oi, mapOf("delta" to it))) }
    add(event("response.reasoning_summary_text.done", oi, mapOf("text" to deltas.joinToString(""))))
    add(event("response.reasoning_summary_part.done", oi))
    add(itemDone(oi, summaryOnDone))
}

private fun itemDone(oi: Int, summary: String?): JsonObject {
    val parts = summary?.let { """[{"type":"summary_text","text":"$it"}]""" } ?: "[]"
    return ev(
        """{"type":"response.output_item.done","output_index":$oi,""" +
            """"item":{"type":"reasoning","id":"rs_$oi","summary":$parts}}""",
    )
}

/** A header split mid-word, the way the backend's deltas cut it. */
private fun split(header: String): List<String> = listOf(header.take(header.length / 2), header.drop(header.length / 2))

private fun rawItem(oi: Int, delta: String, whole: String? = null): List<JsonObject> {
    val item = buildJsonObject {
        put("type", "reasoning")
        put("id", "rs_$oi")
        whole?.let { text ->
            val part = buildJsonObject {
                put("type", "reasoning_text")
                put("text", text)
            }
            put("content", JsonArray(listOf(part)))
        }
    }
    return listOf(
        ev("""{"type":"response.output_item.added","output_index":$oi,"item":{"type":"reasoning","id":"rs_$oi"}}"""),
        event("response.reasoning_text.delta", oi, mapOf("delta" to delta)),
        buildJsonObject {
            put("type", "response.output_item.done")
            put("output_index", oi)
            put("item", item)
        },
    )
}

private fun summaryItem(parts: List<String>): JsonObject = buildJsonObject {
    put("type", "reasoning")
    put("id", "rs_0")
    put("encrypted_content", "opaque-reasoning")
    put(
        "summary",
        JsonArray(
            parts.map { text ->
                buildJsonObject { put("type", "summary_text"); put("text", text) }
            },
        ),
    )
}

private fun itemEvent(type: String, oi: Int, item: JsonObject): JsonObject = buildJsonObject {
    put("type", type)
    put("output_index", oi)
    put("item", item)
}

private fun summaryPartDone(text: String): JsonObject = buildJsonObject {
    put("type", "response.reasoning_summary_part.done")
    put("output_index", 0)
    put("part", buildJsonObject { put("type", "summary_text"); put("text", text) })
}

private fun terminalOutput(items: List<JsonObject>): JsonObject = buildJsonObject {
    put("type", "response.completed")
    put(
        "response",
        buildJsonObject {
            put("id", "r1")
            put("status", "completed")
            put("output", JsonArray(items))
        },
    )
}

private fun visibleThinking(sink: RecordingSink): String =
    sink.calls.filter { it.startsWith("think#") }.joinToString("") { it.substringAfter(':') }

class SummaryStatusHeadersTest {

    @Test
    fun `terminal model-only backfill cannot corrupt a later held-prefix delivery proof`() = runTest {
        val sink = RecordingSink()
        val frames = listOf(
            event("response.reasoning_summary_text.delta", 2, mapOf("delta" to "<!")),
            terminalOutput(listOf(summaryItem(listOf("Completed model text.")))),
        )
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(frames.asFlow(), sink)
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals("<!", visibleThinking(sink))
    }

    @Test
    fun `an oversized terminal suffix never reaches the client`() = runTest {
        val prefix = "**Checking**\n\nThe delivered paragraph."
        val sink = RecordingSink()
        val frames = listOf(
            itemEvent("response.output_item.added", 0, summaryItem(emptyList())),
            event("response.reasoning_summary_text.delta", 0, mapOf("delta" to prefix)),
            terminalOutput(listOf(summaryItem(listOf(prefix + "x".repeat(BufferCapacity.MAX_BUFFERED_CHARS))))),
        )
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(frames.asFlow(), sink)
        assertTrue(outcome is TurnOutcome.Failure)
        val failure = outcome as TurnOutcome.Failure
        assertEquals("upstream: response exceeded max buffered size; aborting", failure.evidence.message)
        assertTrue(!failure.cause.providerReported)
        assertEquals(prefix, visibleThinking(sink))
    }

    @Test
    fun `sparse completions recover the paragraph after a code-mode boundary exactly once`() = runTest {
        val header = "**Checking the request**"
        val whole = "$header\n\nThe completed request retains every reasoning property."
        val exec = ev(
            """{"type":"custom_tool_call","id":"ct_1","call_id":"exec_1","name":"exec","input":"return 7;"}""",
        )
        for (carrier in listOf("text", "part", "item", "terminal")) {
            val sink = RecordingSink()
            val upstream = flow {
                emit(itemEvent("response.output_item.added", 0, summaryItem(emptyList())))
                emit(event("response.reasoning_summary_part.added", 0))
                emit(event("response.reasoning_summary_text.delta", 0, mapOf("delta" to header)))
                emit(itemEvent("response.output_item.added", 1, exec))
                emit(itemEvent("response.output_item.done", 1, exec))
                assertEquals(header, visibleThinking(sink))
                if (carrier == "text") {
                    repeat(2) { emit(event("response.reasoning_summary_text.done", 0, mapOf("text" to whole))) }
                }
                if (carrier == "part") {
                    repeat(2) { emit(summaryPartDone(whole)) }
                }
                val item = summaryItem(listOf(if (carrier == "terminal") header else whole))
                emit(itemEvent("response.output_item.done", 0, item))
                emit(terminalOutput(listOf(summaryItem(listOf(whole)), exec)))
            }
            val context = ctx(
                collect = true,
            ).let { it.copy(reasoningCapture = it.reasoningCapture.copy(codeMode = true)) }
            val outcome = ResponsesStreamTranslator(context).driveTurn(upstream, sink) as TurnOutcome.Success
            assertEquals(whole, visibleThinking(sink), carrier)
            assertEquals(whole, outcome.text.thinkingText, carrier)
        }
    }

    @Test
    fun `completed multi-part snapshots preserve each part once with explicit or implicit indices`() = runTest {
        val parts = listOf("**Read**\n\nThe first paragraph.", "**Check**\n\nThe second paragraph.")
        for (explicit in listOf(false, true)) {
            val sink = RecordingSink()
            val frames = buildList {
                add(itemEvent("response.output_item.added", 0, summaryItem(emptyList())))
                parts.forEachIndexed { index, text ->
                    for ((type, field, value) in listOf(
                        Triple("response.reasoning_summary_part.added", "", ""),
                        Triple("response.reasoning_summary_text.delta", "delta", text.substringBefore("\n")),
                        Triple("response.reasoning_summary_text.done", "text", text),
                        Triple("response.reasoning_summary_text.done", "text", text),
                    )) {
                        add(
                            buildJsonObject {
                                put("type", type)
                                put("output_index", 0)
                                if (explicit) put("summary_index", index)
                                if (field.isNotEmpty()) put(field, value)
                            },
                        )
                    }
                }
                add(itemEvent("response.output_item.done", 0, summaryItem(parts)))
                add(completed)
            }
            val outcome = ResponsesStreamTranslator(ctx()).driveTurn(frames.asFlow(), sink) as TurnOutcome.Success
            val expected = parts.joinToString("\n\n")
            assertEquals(expected, visibleThinking(sink))
            assertEquals(expected, outcome.text.thinkingText)
        }
    }

    @Test
    fun `a divergent completed copy cannot rewrite already streamed reasoning`() = runTest {
        val text = "**Checking**\n\nThe original model paragraph."
        val sink = RecordingSink()
        val frames = liveItem(0, listOf(text)).toMutableList()
        frames.add(
            3,
            event("response.reasoning_summary_text.done", 0, mapOf("text" to "**Different**\n\nA replacement.")),
        )
        val outcome = ResponsesStreamTranslator(
            ctx(),
        ).driveTurn((frames + completed).asFlow(), sink) as TurnOutcome.Success
        assertEquals(text, visibleThinking(sink))
        assertEquals(text, outcome.text.thinkingText)
    }

    @Test
    fun `incomplete comment prefixes are literal at summary completion or item completion`() = runTest {
        for (literal in listOf("<", "<!", "<!-")) {
            val sink = RecordingSink()
            val first = liveItem(0, listOf(literal), summaryOnDone = literal)
            val omitted = setOf("response.reasoning_summary_text.done", "response.reasoning_summary_part.done")
            val upstream = flow {
                first.take(3).forEach { emit(it) }
                assertTrue(sink.calls.none { it.startsWith("openThinking") }, "a live prefix must stay held")
                first.drop(3).forEach { emit(it) }
                liveItem(1, listOf(literal), summaryOnDone = literal)
                    .filter { JsonScalars.str(it, "type") !in omitted }.forEach { emit(it) }
                emit(completed)
            }
            val outcome = ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink) as TurnOutcome.Success
            assertEquals(
                listOf(literal, literal),
                sink.calls.filter { it.startsWith("think#") }.map { it.substringAfter(':') },
            )
            assertEquals("$literal\n\n$literal", outcome.text.thinkingText)
        }
    }

    @Test
    fun `raw prefix completion emits once and cannot carry into a later item`() = runTest {
        val cases = listOf(
            Triple("<", null, "<"),
            Triple("<!", null, "<!"),
            Triple("<!-", null, "<!-"),
            Triple("<", "<!-", "<!-"),
            Triple("<", "<!-- -->", ""),
            Triple("<!-- -->", null, ""),
            Triple("  ", null, ""),
        )
        for ((delta, whole, expected) in cases) {
            val sink = RecordingSink()
            val first = rawItem(0, delta, whole)
            val upstream = flow {
                first.take(2).forEach { emit(it) }
                assertTrue(sink.calls.none { it.startsWith("openThinking") }, "a live prefix must stay held")
                first.drop(2).forEach { emit(it) }
                rawItem(1, "Later.").forEach { emit(it) }
                emit(completed)
            }
            val outcome = ResponsesStreamTranslator(ctx()).driveTurn(upstream, sink) as TurnOutcome.Success
            val parts = listOfNotNull(expected.takeIf { it.isNotEmpty() }, "Later.")
            assertEquals(parts, sink.calls.filter { it.startsWith("think#") }.map { it.substringAfter(':') })
            assertEquals(parts.joinToString("\n\n"), outcome.text.thinkingText)
        }
    }

    @Test
    fun `blank and comment-only summary parts never open thinking blocks`() = runTest {
        val sink = RecordingSink()
        val frames = liveItem(0, listOf(" ", "  ")) + liveItem(1, listOf("<!--", " -->")) +
            liveItem(2, listOf("**Checking**", "\n\n", "<!-- -->")) + completed
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(frames.asFlow(), sink) as TurnOutcome.Success
        assertEquals("", outcome.text.thinkingText)
        assertTrue(!outcome.text.emittedThinking)
        assertTrue(sink.calls.none { it.startsWith("openThinking") || it.startsWith("think#") })
    }

    @Test
    fun `comment markers followed by real model prose remain unchanged`() = runTest {
        val text = "<!-- --> is quoted model text."
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            (liveItem(0, listOf("<!--", " -->", " is quoted model text.")) + completed).asFlow(),
            sink,
        ) as TurnOutcome.Success
        assertEquals(text, outcome.text.thinkingText)
        assertTrue(outcome.text.emittedThinking)
    }

    @Test
    fun `the captured gpt-6-sol round renders each of its nine headers once`() = runTest {
        val events = ROUND_ORDER.mapIndexed { oi, c -> liveItem(oi, split(HEADERS[c.digitToInt()])) }.flatten()
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn((events + completed).asFlow(), sink)

        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(HEADERS.size, sink.calls.count { it.startsWith("openThinking") }, "blocks: ${sink.calls}")
        HEADERS.forEach { header ->
            assertEquals(1, sink.calls.count { it.endsWith(":$header") }, "$header in ${sink.calls}")
        }
        assertEquals(0, sink.calls.count { it.endsWith(":\n\n") }, "a held part left a break: ${sink.calls}")
    }

    @Test
    fun `a header with a body streams live, before the part's done`() = runTest {
        val deltas = listOf("**Evaluating", " screenshot capture**", "\n\n", "I need to create ", "a script.")
        val sink = RecordingSink()
        val outcome = ResponsesStreamTranslator(ctx()).driveTurn(
            (liveItem(0, deltas) + completed).asFlow(),
            sink,
        )

        val text = deltas.joinToString("")
        assertEquals(text, (outcome as TurnOutcome.Success).text.thinkingText)
        // Released at the body's first delta: the header and that delta in one write, then the rest.
        assertEquals(
            listOf("think#0:**Evaluating screenshot capture**\n\nI need to create ", "think#0:a script."),
            sink.calls.filter { it.startsWith("think#") },
        )
    }

    @Test
    fun `a repeated header-only item renders nothing, its completed item included`() = runTest {
        val header = HEADERS[1]
        val sink = RecordingSink()
        ResponsesStreamTranslator(ctx()).driveTurn(
            (liveItem(0, listOf(header), header) + liveItem(1, listOf(header), header) + completed).asFlow(),
            sink,
        )

        assertEquals(1, sink.calls.count { it.startsWith("openThinking") }, "calls: ${sink.calls}")
        assertEquals(1, sink.calls.count { it.endsWith(":$header") }, "calls: ${sink.calls}")
    }

    @Test
    fun `a backend that sends only completed items shows a repeated bare header once`() = runTest {
        val header = HEADERS[3]
        val body = "**Reviewing code and log**\n\nThe ledger row names the file."
        val added = { oi: Int ->
            ev("""{"type":"response.output_item.added","output_index":$oi,"item":{"type":"reasoning","id":"rs_$oi"}}""")
        }
        val sink = RecordingSink()
        ResponsesStreamTranslator(ctx()).driveTurn(
            listOf(
                added(0),
                itemDone(0, header),
                added(1),
                itemDone(1, header),
                added(2),
                itemDone(2, body.replace("\n", "\\n")),
                completed,
            ).asFlow(),
            sink,
        )

        assertEquals(2, sink.calls.count { it.startsWith("openThinking") }, "calls: ${sink.calls}")
        assertEquals(1, sink.calls.count { it.endsWith(":$header") }, "calls: ${sink.calls}")
        assertEquals(1, sink.calls.count { it.endsWith(":$body") }, "a header with a body is reasoning: ${sink.calls}")
    }
}
