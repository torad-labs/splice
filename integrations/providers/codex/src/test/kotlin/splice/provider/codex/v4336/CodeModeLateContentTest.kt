// NEW: V4-336 — a splice_exec script whose every callback returned is not cut off by client content
// that arrives after the results. Claude Code sends a peer's message, a task notification or a hook's
// output as a role=system message, and it lands behind the callback results; live on 2026-09-26, 86 of
// the claudex head's 128 code-mode records ended "additional client content arrived", 85 of them with
// every result back, and the model re-issued the same batch. The script gets its results, then the
// model gets the script's output and, after it, the new content. What the operator typed (a user
// message) still stops the script, and so does any content while a callback is still unanswered.
package splice.provider.codex.v4336

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.TurnOutcome
import splice.dialect.responses.request.ResponsesContextMessage
import splice.provider.codex.BASE_REQUEST
import splice.provider.codex.CodeModeBridgeTestSupport
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.terminatedEvidence
import splice.provider.codex.turn
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep

private const val LATE = "Another Claude session sent a message: the build is green."
private const val DEVELOPER = """{"role":"developer","content":"s"}"""

class CodeModeLateContentTest : CodeModeBridgeTestSupport() {

    @Test
    fun `a script whose every callback returned finishes when a system message follows its results - V4-336`() =
        runTest {
            val runtime = runtime(CodeModeStep.Completed("both done"))
            val manager = bridge(runtime)
            val (read, edit) = start(manager)
            val body = appended(requestWithTwoResults(read, edit), message("system", LATE))

            val (outcome, upstream) = resume(manager, body, CodeModeResult(read, "A"), CodeModeResult(edit, "B"))

            assertTrue(outcome is TurnOutcome.Success, outcome.toString())
            assertEquals(2, runtime.cell.advances, "the script got its results")
            assertEquals(listOf("A", "B"), runtime.cell.results.last().map { it.output })
            val input = inputOf(upstream)
            val output = input.indexOfFirst { typeOf(it) == "custom_tool_call_output" }
            assertEquals(
                "Script completed\nWall time 0.0 seconds\nOutput:\nboth done",
                input[output].jsonObject.getValue("output").jsonPrimitive.content,
            )
            assertEquals(message("system", LATE), input.drop(output + 1).single(), "the new content follows the output")
        }

    @Test
    fun `the lite context message a system message becomes also lets the finished script complete - V4-390`() =
        runTest {
            val runtime = runtime(CodeModeStep.Completed("both done"))
            val manager = bridge(runtime)
            val (read, edit) = start(manager)
            val late = ResponsesContextMessage.item(LATE)
            val body = appended(requestWithTwoResults(read, edit), late)

            val (outcome, upstream) = resume(manager, body, CodeModeResult(read, "A"), CodeModeResult(edit, "B"))

            assertTrue(outcome is TurnOutcome.Success, outcome.toString())
            assertEquals(2, runtime.cell.advances, "a context message is not the operator steering the script")
            val input = inputOf(upstream)
            val output = input.indexOfFirst { typeOf(it) == "custom_tool_call_output" }
            assertEquals(late, input.drop(output + 1).single(), "the context message follows the output")
        }

    @Test
    fun `text the operator typed after every result still stops the script - V4-336`() = runTest {
        val runtime = runtime(CodeModeStep.Completed("both done"))
        val manager = bridge(runtime)
        val (read, edit) = start(manager)
        val body = appended(requestWithTwoResults(read, edit), message("user", "stop, use the other file"))

        val (outcome, upstream) = resume(manager, body, CodeModeResult(read, "A"), CodeModeResult(edit, "B"))

        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        assertEquals(1, runtime.cell.advances, "steering stops the script where it is")
        assertEquals(emptyList<String>(), unresolved(upstream))
    }

    @ParameterizedTest
    @ValueSource(strings = ["system", "user"])
    fun `a callback still unanswered when content arrives stops the script there - V4-336`(role: String) = runTest {
        val runtime = runtime()
        val manager = bridge(runtime)
        val (read, edit) = start(manager)
        val body = """{"input":[{"role":"developer","content":"s"},""" +
            """{"type":"function_call","call_id":"$read","name":"Read","arguments":"{}"},""" +
            """{"type":"function_call_output","call_id":"$read","output":"A"},""" +
            """{"type":"function_call","call_id":"$edit","name":"Edit","arguments":"{}"},""" +
            """{"role":"$role","content":"$LATE"}]}"""

        val (outcome, upstream) = resume(manager, body, CodeModeResult(read, "A"))

        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        assertEquals(1, runtime.cell.advances, "the script does not go on past a call nobody answered")
        assertEquals(listOf(edit), unresolved(upstream))
    }

    @Test
    fun `a sequential batch with a call not yet exposed stops when a system message arrives - V4-336`() = runTest {
        val runtime = runtime()
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = true).intercept(BASE_REQUEST, sink) {
            RoundResult.Outcome(outerOutcome())
        }
        val read = sink.tools.single().id
        val body = appended(requestWithResult(read, "A"), message("system", LATE))

        var upstream = ""
        val outcome = manager.interceptor(turn(results = listOf(CodeModeResult(read, "A"))), disableParallel = true)
            .intercept(body, RecordingSink()) {
                upstream = it
                RoundResult.Outcome(completedOutcome())
            }.turn()

        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        assertEquals(1, runtime.cell.advances, "the Edit the batch still holds was never scheduled")
        assertEquals(1, unresolved(upstream).size)
    }

    @Test
    fun `a replay item nothing put there still stops the script - V4-336`() = runTest {
        val runtime = runtime(CodeModeStep.Completed("both done"))
        val manager = bridge(runtime)
        val (read, edit) = start(manager)
        val reasoning = Json.parseToJsonElement("""{"type":"reasoning","id":"rs_late","summary":[]}""")
        val body = appended(requestWithTwoResults(read, edit), reasoning)

        val (outcome, upstream) = resume(manager, body, CodeModeResult(read, "A"), CodeModeResult(edit, "B"))

        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        assertEquals(1, runtime.cell.advances, "only a plain system message waits for the output")
        assertEquals(emptyList<String>(), unresolved(upstream))
    }

    @Test
    fun `a history whose baseline was edited still stops the script - V4-336`() = runTest {
        val runtime = runtime(CodeModeStep.Completed("both done"))
        val manager = bridge(runtime)
        val (read, edit) = start(manager, opening("fix the build"))
        val edited = "$DEVELOPER,${message("user", "fix the tests")}"
        val body = requestWithTwoResults(read, edit).replace(DEVELOPER, edited)

        val (outcome, _) = resume(manager, body, CodeModeResult(read, "A"), CodeModeResult(edit, "B"))

        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        assertEquals(1, runtime.cell.advances, "a moved baseline abandons the script before its results are read")
    }

    /** A script that asks for a Read and an Edit at once, then takes [after]. */
    private fun runtime(vararg after: CodeModeStep) = ScriptedRuntime(
        ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"), call("edit", "Edit")))) + after),
    )

    /** The outer call starts the script on [body]; the two client call ids it exposed. */
    private suspend fun start(manager: CodexCodeModeBridge, body: String = BASE_REQUEST): Pair<String, String> {
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(body, sink) {
            RoundResult.Outcome(outerOutcome())
        }
        val (read, edit) = sink.tools.map { it.id }
        return read to edit
    }

    /** The next request, carrying [results]: its outcome and the body it posted upstream. */
    private suspend fun resume(
        manager: CodexCodeModeBridge,
        body: String,
        vararg results: CodeModeResult,
    ): Pair<TurnOutcome, String> {
        var upstream = ""
        val outcome = manager.interceptor(turn(results = results.toList()), disableParallel = false)
            .intercept(body, RecordingSink()) {
                upstream = it
                RoundResult.Outcome(completedOutcome())
            }
        return outcome.turn() to upstream
    }

    /** The interruption evidence's unresolved call ids; the output must be an interruption. */
    private fun unresolved(upstream: String): List<String> {
        val output = inputOf(upstream).single { typeOf(it) == "custom_tool_call_output" }
        val evidence = terminatedEvidence(output.jsonObject.getValue("output").jsonPrimitive.content)
        assertEquals("interrupted", evidence.getValue("status").jsonPrimitive.content)
        return evidence.getValue("unresolved").jsonArray.map { idOf(it) }
    }

    /** The conversation the script starts on: the operator's one message, after the preamble. */
    private fun opening(text: String): String = """{"input":[$DEVELOPER,${message("user", text)}]}"""

    private fun message(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    private fun appended(body: String, item: JsonElement): String {
        val root = Json.parseToJsonElement(body).jsonObject
        return JsonObject(root + ("input" to JsonArray(root.getValue("input").jsonArray + item))).toString()
    }

    private fun inputOf(body: String): List<JsonElement> =
        Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray

    private fun idOf(item: JsonElement): String = item.jsonObject.getValue("id").jsonPrimitive.content

    private fun typeOf(item: JsonElement): String? = (item.jsonObject["type"] as? JsonPrimitive)?.content
}
