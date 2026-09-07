import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.spi.CodeModeResult
import splice.spi.CodeModeStep
import java.nio.file.Files

class CodexCodeModeInterruptionTest : CodeModeBridgeTestSupport() {
    @Test
    fun `aggregate interruption evidence past the worker text limit is posted whole`() = runTest {
        // Two results that each fit the worker's 64 KiB text frame but together do not. The evidence
        // never crosses that frame — it is the outer call's own output — so it goes upstream complete;
        // grading it against the frame poisoned the record and every retry of the same request.
        val steps = listOf(
            CodeModeStep.Calls(listOf(call("first", "Read"), call("second", "Edit"))),
            CodeModeStep.Calls(listOf(call("later", "Read"))),
        )
        val runtime = ScriptedRuntime(ArrayDeque(steps))
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) { outerOutcome() }
        val results = sink.tools.map { CodeModeResult(it.id, "é".repeat(20_000), true) }
        val returned = turn(results = results)
        var upstream = ""
        val outcome = manager.interceptor(returned, disableParallel = false)
            .intercept(siblingResults(results), RecordingSink()) {
                upstream = it
                completedOutcome()
            }
        assertTrue(outcome is TurnOutcome.Success, outcome.toString())
        val items = Json.parseToJsonElement(upstream).jsonObject.getValue("input").jsonArray
        val output = items.single { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
            .jsonObject.getValue("output").jsonPrimitive.content
        assertTrue(output.encodeToByteArray().size > 65_536)
        val posted = Json.parseToJsonElement(output).jsonObject.getValue("results").jsonArray
            .associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        results.forEach { result ->
            assertEquals(result.output, posted.getValue(result.id)["output"]?.jsonPrimitive?.content)
            assertEquals("true", posted.getValue(result.id)["isError"]?.jsonPrimitive?.content)
        }
        val saved = Json.parseToJsonElement(Files.readString(tempDir.resolve("bridge.json"))).jsonObject
            .getValue("records").jsonArray.single().jsonObject
        assertEquals("COMPLETED", saved.getValue("phase").jsonPrimitive.content)
        assertTrue(runtime.cell.closed)
        assertEquals(1, runtime.starts)
    }

    @Test
    fun `lost partial batch preserves evidence and identifies the missing callback`() = runTest {
        val batch = CodeModeStep.Calls(listOf(call("first", "Read"), call("second", "Edit")))
        val runtime = ScriptedRuntime(ArrayDeque(listOf(batch)))
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) { outerOutcome() }
        manager.onHeadStop()
        val result = CodeModeResult(sink.tools.first().id, "finished", true)
        var upstream = ""
        val outcome = manager.interceptor(turn(results = listOf(result)), disableParallel = false)
            .intercept(siblingResults(listOf(result)), RecordingSink()) {
                upstream = it
                completedOutcome()
            }
        assertTrue(outcome is TurnOutcome.Success)
        val items = Json.parseToJsonElement(upstream).jsonObject.getValue("input").jsonArray
        val output = items.single { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
            .jsonObject.getValue("output").jsonPrimitive.content
        val evidence = Json.parseToJsonElement(output).jsonObject
        val completed = evidence.getValue("results").jsonArray.single().jsonObject
        assertEquals("finished", completed.getValue("output").jsonPrimitive.content)
        val unresolved = evidence.getValue("unresolved").jsonArray.single().jsonObject
        assertEquals(sink.tools.last().id, unresolved.getValue("id").jsonPrimitive.content)
        assertEquals("result unavailable", unresolved.getValue("status").jsonPrimitive.content)
        assertEquals(1, runtime.starts)
    }

    @Test
    fun `lost continuation rejects fabricated unexposed callbacks without consuming evidence`() = runTest {
        val batch = CodeModeStep.Calls(listOf(call("first", "Read"), call("second", "Edit")))
        val runtime = ScriptedRuntime(ArrayDeque(listOf(batch)))
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = true).intercept(BASE_REQUEST, sink) { outerOutcome() }
        manager.onHeadStop()
        val state = Files.readString(tempDir.resolve("bridge.json"))
        val pending = Json.parseToJsonElement(state).jsonObject.getValue("records").jsonArray.single().jsonObject
            .getValue("pending").jsonArray.last().jsonObject.getValue("clientId").jsonPrimitive.content
        val result = CodeModeResult(pending, "never executed")
        val outcome = manager.interceptor(turn(results = listOf(result)), disableParallel = true)
            .intercept(siblingResults(listOf(result)), RecordingSink()) { error("must not post fabricated evidence") }
        assertTrue(outcome is TurnOutcome.Failure)
        assertTrue((outcome as TurnOutcome.Failure).message.contains("not exposed"))
        assertEquals(state, Files.readString(tempDir.resolve("bridge.json")))
        assertEquals(1, runtime.cell.advances)
    }

    @Test
    fun `lost continuation rejects conflicting already accepted results`() = runTest {
        val batch = CodeModeStep.Calls(listOf(call("first", "Read"), call("second", "Edit")))
        val runtime = ScriptedRuntime(ArrayDeque(listOf(batch)))
        val manager = bridge(runtime)
        val first = RecordingSink()
        manager.interceptor(turn(), disableParallel = true).intercept(BASE_REQUEST, first) { outerOutcome() }
        val id = first.tools.single().id
        manager.interceptor(turn(id, "original"), disableParallel = true)
            .intercept(requestWithResult(id, "original"), RecordingSink()) { error("must not post") }
        manager.onHeadStop()
        val before = Files.readString(tempDir.resolve("bridge.json"))
        val changed = CodeModeResult(id, "conflict")
        val outcome = manager.interceptor(turn(results = listOf(changed)), disableParallel = true)
            .intercept(siblingResults(listOf(changed)), RecordingSink()) { error("must not post conflict") }
        assertTrue(outcome is TurnOutcome.Failure)
        assertTrue((outcome as TurnOutcome.Failure).message.contains("conflicting replay"))
        assertEquals(before, Files.readString(tempDir.resolve("bridge.json")))
        assertFalse(runtime.starts > 1)
    }

    @Test
    fun `catalog change interruption preserves the result already returned by the client`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"))))))
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) { outerOutcome() }
        val result = CodeModeResult(sink.tools.single().id, "completed before catalog changed", true)
        val changed = turn(results = listOf(result)).copy(tools = setOf("Edit"))
        var upstream = ""
        val outcome = manager.interceptor(changed, disableParallel = false)
            .intercept(siblingResults(listOf(result)), RecordingSink()) {
                upstream = it
                completedOutcome()
            }
        assertTrue(outcome is TurnOutcome.Success)
        val items = Json.parseToJsonElement(upstream).jsonObject.getValue("input").jsonArray
        val output = items.single { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
            .jsonObject.getValue("output").jsonPrimitive.content
        val evidence = Json.parseToJsonElement(output).jsonObject
        val completed = evidence.getValue("results").jsonArray.single().jsonObject
        assertEquals(result.output, completed.getValue("output").jsonPrimitive.content)
        assertEquals("true", completed.getValue("isError").jsonPrimitive.content)
        assertEquals(1, runtime.cell.advances)
    }

    private fun siblingResults(results: List<CodeModeResult>): String {
        val base = Json.parseToJsonElement(BASE_REQUEST).jsonObject.getValue("input").jsonArray
        val items = base + JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive("continue"))) +
            results.flatMap { result ->
                listOf(
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("function_call"),
                            "call_id" to JsonPrimitive(result.id),
                            "name" to JsonPrimitive("Read"),
                            "arguments" to JsonPrimitive("{}"),
                        ),
                    ),
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("function_call_output"),
                            "call_id" to JsonPrimitive(result.id),
                            "output" to JsonPrimitive(result.output),
                        ),
                    ),
                )
            }
        return JsonObject(mapOf("input" to JsonArray(items))).toString()
    }
}
