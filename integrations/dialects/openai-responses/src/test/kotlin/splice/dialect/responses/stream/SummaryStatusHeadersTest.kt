// NEW: V4-450 — header-only reasoning-summary parts render once per round per distinct text.
package splice.dialect.responses.stream

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome

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

class SummaryStatusHeadersTest {

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
