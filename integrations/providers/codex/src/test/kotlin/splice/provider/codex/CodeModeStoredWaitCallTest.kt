// Fix 1 of Oct 27: every request a code-mode head posts answers each call once, with the result GPT saw, and never
// answers with a stand-in a call whose result existed. A wait record stored before that day keeps its own wait call
// among the items after its call, because the fold reported the call that sealed the round a second time. When the
// rebuild cannot place such a record, its client calls stay in the history as ordinary tool calls and no script
// output goes out, so the stored copy of the wait went out with no result: the 04:55 CT refusal, "No tool output
// found for function call". Each post here goes through the wire's own rewrite and its upstream body, as a round does.
package splice.provider.codex

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.provider.codex.history.CodexCodeModeHistory
import splice.provider.codex.state.CodeModeStateJournal
import splice.upstream.RoundBody
import splice.upstream.codemode.CodeModeResult
import java.nio.file.Files
import java.nio.file.Path

/** The call kinds a Responses input pairs with a result, and the result kind that answers each. */
private val ANSWERS = mapOf(
    "function_call" to "function_call_output",
    "custom_tool_call" to "custom_tool_call_output",
)

/** Codex's result for a call that never ran (codex-rs context_manager/normalize.rs). */
private const val ABORTED = "aborted"
private const val CLIENT_RESULT = "synthetic file contents"
private const val WAIT_RESULT = "synthetic script output"

internal class CodeModeStoredWaitCallTest {
    private val history = CodexCodeModeHistory(Json)
    private val first = item("""{"role":"user","content":"synthetic first request"}""")

    /** Reasoning as the stream sent it, with its empty content. */
    private val streamed = item(
        """{"type":"reasoning","id":"rs-synthetic","summary":[],"content":[],"encrypted_content":"synthetic bytes"}""",
    )

    /** The same reasoning as the dialect puts it back in the client's history, without the empty content. */
    private val cached = JsonObject(streamed.jsonObject - "content")

    @Test
    fun `a stored wait the rebuild cannot place goes out with no stand-in, and the client's result goes out`(
        @TempDir dir: Path,
    ) {
        val wait = stored(dir, storedWait(baseline = listOf(first, streamed)))
        val posted = post(listOf(first, cached) + callbacks(wait), wait, placed = false)

        assertEquals(emptyList<String>(), problems(posted), "the posted input:\n${shape(posted)}")
        val read = posted.single { JsonScalars.str(it, "call_id") == "callback-${wait.id}" && output(it) }
        assertEquals(JsonPrimitive(CLIENT_RESULT), read["output"], "the client's own result goes out")
    }

    @Test
    fun `a stored wait the rebuild places goes out once, followed by its own output`(@TempDir dir: Path) {
        val wait = stored(dir, storedWait(baseline = listOf(first, streamed)))
        val posted = post(listOf(first, streamed) + callbacks(wait), wait, placed = true)

        assertEquals(emptyList<String>(), problems(posted), "the posted input:\n${shape(posted)}")
        val calls = posted.filter { JsonScalars.str(it, "call_id") == wait.id }
        assertEquals(listOf("function_call", "function_call_output"), calls.map { JsonScalars.str(it, "type") })
        assertEquals(JsonPrimitive(WAIT_RESULT), calls.last()["output"], "the wait's own output goes out")
    }

    /** One post of [client] with [wait] stored: the wire's rewrite, then the body it sends upstream. */
    private fun post(client: List<JsonElement>, wait: CodeModeRecord, placed: Boolean): List<JsonObject> {
        val wire = CodexCodeModeWire(Json, LogSink {})
        val rewrite = wire.canonicalize(body(client), listOf(wait))
        val reasons = rewrite.omitted.map { it.reason }
        assertEquals(placed, reasons.isEmpty(), "the rebuild ${if (placed) "places" else "cannot place"} it: $reasons")
        val upstream = wire.upstream(checkNotNull(rewrite.body) { "no body: ${rewrite.error}" })
        return checkNotNull(CodeModeBody(upstream, Json).request).second.map { it.jsonObject }
    }

    /** [record] written to a state journal and read back, as a daemon finds the records an earlier build stored. */
    private fun stored(dir: Path, record: CodeModeRecord): CodeModeRecord {
        val file = dir.resolve("stored-wait.jsonl")
        Files.writeString(file, Json.encodeToString(CodeModePersistedState(records = listOf(record.snapshot()))) + "\n")
        return CodeModeStateJournal.read(file, Json).records.single().restore()
    }

    /** A completed wait as records stored before Oct 27 hold it: its own wait call is among its items after the call,
     *  and the Read it asked the client for was answered. */
    private fun storedWait(baseline: List<JsonElement>): CodeModeRecord {
        val boundary = checkNotNull(history.anchoredBoundary(body(baseline), emptyList()))
        val id = "wait-synthetic"
        val outer = item(
            """{"type":"function_call","id":"fc-synthetic","call_id":"$id","name":"wait",""" +
                """"arguments":"{\"cell_id\":\"1\",\"yield_time_ms\":1000}"}""",
        ).jsonObject
        return CodeModeRecord(
            id = id,
            key = "synthetic-conversation",
            phase = CodeModePhase.COMPLETED,
            origin = CodeModeOrigin(
                outer = outer,
                outerCallId = id,
                source = "",
                baseline = CodeModeBaseline(
                    inputCount = boundary.fullCount,
                    inputDigest = boundary.fullDigest,
                    logicalCount = boundary.logicalCount,
                    logicalDigest = boundary.logicalDigest,
                    metadataVersion = CODE_MODE_METADATA_VERSION,
                ),
            ),
            progress = CodeModeProgress(output = WAIT_RESULT, updatedAt = 1_000, lastDigest = "synthetic-request"),
            carry = CodeModeNativeContinuity(
                segments = boundary.nativeSegments,
                continuity = emptyList(),
                replay = emptyList(),
                nativeAfterExec = listOf(outer),
            ),
        ).also { record ->
            record.replayAnchors = boundary.replayAnchors
            record.accepted.accept(
                mapOf("callback-$id" to CodeModeResult("callback-$id", CLIENT_RESULT)),
                emptyMap(),
            )
            record.issued += CodeModeIssuedStep(
                "synthetic-request",
                listOf(CodeModePending("runtime-$id", "callback-$id", "Read", JsonObject(emptyMap()), true)),
            )
        }
    }

    /** What breaks the goal in one posted input: a call twice, a call with no result after it, a stand-in for a call
     *  whose result existed (every call here had one), a result with no call, a reasoning id twice. */
    private fun problems(items: List<JsonObject>): List<String> {
        val calls = items.withIndex().filter { ANSWERS.containsKey(JsonScalars.str(it.value, "type")) }
        val twice = calls.groupBy { JsonScalars.str(it.value, "call_id") }.filterValues { it.size > 1 }
            .keys.map { "call $it goes out more than once" }
        val unanswered = calls.mapNotNull { (at, call) ->
            val id = JsonScalars.str(call, "call_id")
            val kind = ANSWERS.getValue(checkNotNull(JsonScalars.str(call, "type")))
            val result = items.drop(at + 1).firstOrNull {
                JsonScalars.str(it, "type") == kind && JsonScalars.str(it, "call_id") == id
            }
            when {
                result == null -> "call $id goes out with no result after it"
                result["output"] == JsonPrimitive(ABORTED) -> "call $id goes out with a stand-in for its result"
                else -> null
            }
        }
        val orphans = items.filter(::output).mapNotNull { result ->
            val id = JsonScalars.str(result, "call_id")
            "result $id goes out without its call".takeIf { calls.none { JsonScalars.str(it.value, "call_id") == id } }
        }
        val reasoning = items.filter { JsonScalars.str(it, "type") == "reasoning" }
            .groupingBy { JsonScalars.str(it, "id") }.eachCount().filterValues { it > 1 }
            .keys.map { "reasoning $it goes out more than once" }
        return twice + unanswered + orphans + reasoning
    }

    private fun output(item: JsonObject): Boolean = JsonScalars.str(item, "type") in ANSWERS.values

    private fun shape(items: List<JsonObject>): String = items.joinToString("\n") { "  $it" }

    private fun callbacks(record: CodeModeRecord): List<JsonElement> = listOf(
        item("""{"type":"function_call","call_id":"callback-${record.id}","name":"Read","arguments":"{}"}"""),
        item("""{"type":"function_call_output","call_id":"callback-${record.id}","output":"$CLIENT_RESULT"}"""),
    )

    private fun body(items: List<JsonElement>): CodeModeBody =
        CodeModeBody(RoundBody.Tree(JsonObject(mapOf("input" to JsonArray(items)))), Json)

    private fun item(text: String): JsonElement = Json.parseToJsonElement(text)
}
