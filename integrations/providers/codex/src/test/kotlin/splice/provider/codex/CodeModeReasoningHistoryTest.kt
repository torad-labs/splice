// NEW: a parked script is not cut off by the reasoning the client replays
// from EARLIER turns. The reader counted a replay item as new content when its slot was not among the
// record's native segments, and those hold only replay no function_call follows: every reasoning item
// before an earlier ordinary tool call failed that test. So in any conversation with reasoning, every
// resume read as the operator steering: live after the V4-336 install, 35 of 49 new records ended
// "additional client content arrived" with every result back, and the one caught body carried only a
// role=system message after its results, with 63 reasoning items in its history. The history before
// the baseline is already held by its digest and its native segments before the reader runs.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.RoundHandoffs
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep

/** An earlier turn's tool round, its reasoning replayed before its call, as a lite builder sends it. */
private const val OPENING = """{"input":[{"role":"developer","content":"s"},""" +
    """{"role":"user","content":"Fix the build."},""" +
    """{"type":"reasoning","id":"rs_earlier","summary":[],"encrypted_content":"e"},""" +
    """{"type":"function_call","call_id":"call_earlier","name":"Read","arguments":"{}"},""" +
    """{"type":"function_call_output","call_id":"call_earlier","output":"earlier"}]}"""

private const val PREFACE = "I'll read the config and fix it."

class CodeModeReasoningHistoryTest : CodeModeBridgeTestSupport() {

    @Test
    fun `reasoning from an earlier tool call is history, and the script finishes with its results`() =
        runTest {
            val runtime = runtime()
            val manager = bridge(runtime)
            val (read, edit) = start(manager)

            val outcome = resume(manager, history(read, edit), CodeModeResult(read, "A"), CodeModeResult(edit, "B"))

            assertTrue(outcome is TurnOutcome.Success, outcome.toString())
            assertEquals(2, runtime.cell.advances, "nothing new arrived: the script got its results")
        }

    @Test
    fun `a system message after the results waits for the output in a history with reasoning`() =
        runTest {
            val runtime = runtime()
            val manager = bridge(runtime)
            val (read, edit) = start(manager)
            val late = Json.parseToJsonElement("""{"role":"system","content":"A peer's message."}""")

            val outcome = resume(
                manager,
                appended(history(read, edit), late),
                CodeModeResult(read, "A"),
                CodeModeResult(edit, "B"),
            )

            assertTrue(outcome is TurnOutcome.Success, outcome.toString())
            assertEquals(2, runtime.cell.advances, "the script got its results before the message")
        }

    @Test
    fun `a system message with a call unanswered still stops the script in a history with reasoning`() =
        runTest {
            val runtime = runtime()
            val manager = bridge(runtime)
            val (read, edit) = start(manager)
            val late = Json.parseToJsonElement("""{"role":"system","content":"A peer's message."}""")

            resume(manager, appended(history(read, edit, mapOf(read to "A")), late), CodeModeResult(read, "A"))

            assertEquals(1, runtime.cell.advances, "the script does not go on past a call nobody answered")
        }

    @Test
    fun `reasoning the client adds after the results still stops the script`() = runTest {
        val runtime = runtime()
        val manager = bridge(runtime)
        val (read, edit) = start(manager)
        val added = Json.parseToJsonElement("""{"type":"reasoning","id":"rs_added","summary":[]}""")

        resume(manager, appended(history(read, edit), added), CodeModeResult(read, "A"), CodeModeResult(edit, "B"))

        assertEquals(1, runtime.cell.advances, "replay nobody put in the tail is new content")
    }

    @Test
    fun `reasoning right where the script started is new content, not history`() = runTest {
        val runtime = runtime()
        val manager = bridge(runtime)
        val (read, edit) = start(manager)
        val added = Json.parseToJsonElement("""{"type":"reasoning","id":"rs_added","summary":[]}""")
        val late = Json.parseToJsonElement("""{"role":"system","content":"A peer's message."}""")
        val body = history(read, edit, opening = listOf(added, late).fold(OPENING, ::appended))

        resume(manager, body, CodeModeResult(read, "A"), CodeModeResult(edit, "B"))

        assertEquals(1, runtime.cell.advances, "the tail starts where the baseline ends")
    }

    @Test
    fun `a script's own preface, replayed in a history with reasoning, is not new content`() = runTest {
        val runtime = runtime()
        val manager = bridge(runtime)
        val (read, edit) = start(manager, preface())
        val replayed = Json.parseToJsonElement("""{"role":"assistant","content":"$PREFACE"}""")

        resume(
            manager,
            history(read, edit, opening = appended(OPENING, replayed)),
            CodeModeResult(read, "A"),
            CodeModeResult(edit, "B"),
        )

        assertEquals(2, runtime.cell.advances, "the preface is the record's continuity: the script got its results")
    }

    @Test
    fun `reasoning nothing recorded before the script's preface is new content`() = runTest {
        val runtime = runtime()
        val manager = bridge(runtime)
        val (read, edit) = start(manager, preface())
        val added = Json.parseToJsonElement("""{"type":"reasoning","id":"rs_added","summary":[]}""")
        val replayed = Json.parseToJsonElement("""{"role":"assistant","content":"$PREFACE"}""")

        resume(
            manager,
            history(read, edit, opening = listOf(added, replayed).fold(OPENING, ::appended)),
            CodeModeResult(read, "A"),
            CodeModeResult(edit, "B"),
        )

        assertEquals(1, runtime.cell.advances, "the tail starts at the baseline's end, not after the preface")
    }

    /** A script that asks for a Read and an Edit at once, then finishes. */
    private fun runtime() = ScriptedRuntime(
        ArrayDeque(
            listOf(
                CodeModeStep.Calls(listOf(call("read", "Read"), call("edit", "Edit"))),
                CodeModeStep.Completed("both done"),
            ),
        ),
    )

    /** The outer call starts the script on [OPENING]; the two client call ids it exposed. */
    private suspend fun start(
        manager: CodexCodeModeBridge,
        firstTurn: TurnOutcome = outerOutcome(),
    ): Pair<String, String> {
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(OPENING, sink) { RoundResult.Outcome(firstTurn) }
        val (read, edit) = sink.tools.map { it.id }
        return read to edit
    }

    /** The model says [PREFACE], then starts the script. */
    private fun preface() = TurnOutcome.Success(
        hasToolUse = false,
        incomplete = false,
        usage = Usage(),
        text = RoundText(bodyText = PREFACE, emittedText = true),
        handoffs = RoundHandoffs(customCalls = listOf(outer())),
    )

    private suspend fun resume(manager: CodexCodeModeBridge, body: String, vararg results: CodeModeResult) =
        manager.interceptor(turn(results = results.toList()), disableParallel = false)
            .intercept(body, RecordingSink()) { RoundResult.Outcome(completedOutcome()) }.turn()

    /** [opening], then both calls, each with its output where [outputs] has one. */
    private fun history(
        read: String,
        edit: String,
        outputs: Map<String, String> = mapOf(read to "A", edit to "B"),
        opening: String = OPENING,
    ): String {
        val calls = listOf(read to "Read", edit to "Edit").flatMap { (id, name) ->
            listOfNotNull(
                """{"type":"function_call","call_id":"$id","name":"$name","arguments":"{}"}""",
                outputs[id]?.let { """{"type":"function_call_output","call_id":"$id","output":"$it"}""" },
            )
        }
        return calls.fold(opening) { body, item -> appended(body, Json.parseToJsonElement(item)) }
    }

    private fun appended(body: String, item: JsonElement): String {
        val root = Json.parseToJsonElement(body).jsonObject
        return JsonObject(root + ("input" to JsonArray(root.getValue("input").jsonArray + item))).toString()
    }
}
