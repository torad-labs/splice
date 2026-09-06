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

class CodexCodeModeRecoveryTest : CodeModeBridgeTestSupport() {
    @Test
    fun `failed result save retries without losing results or emitting empty tool use`() = runTest {
        val runtime = scripted(
            CodeModeStep.Calls(listOf(call("read", "Read"))),
            CodeModeStep.Completed("done"),
        )
        val manager = bridge(runtime)
        val first = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, first) { outerOutcome() }
        val id = first.tools.single().id
        val state = tempDir.resolve("bridge.json")
        Files.delete(state)
        Files.createDirectory(state)
        val failed = manager.interceptor(turn(id, "A"), disableParallel = false)
            .intercept(requestWithResult(id, "A"), RecordingSink()) { error("must not post") }
        assertTrue(failed is TurnOutcome.Failure)
        assertTrue((failed as TurnOutcome.Failure).message.contains("persist"))
        assertEquals(1, runtime.cell.advances)
        Files.delete(state)
        val retrySink = RecordingSink()
        val retried = manager.interceptor(turn(id, "A"), disableParallel = false)
            .intercept(requestWithResult(id, "A"), retrySink) { completedOutcome() }
        assertTrue(retried is TurnOutcome.Success)
        assertFalse((retried as TurnOutcome.Success).hasToolUse)
        assertTrue(retrySink.tools.isEmpty())
        assertEquals(2, runtime.cell.advances)
        assertEquals(listOf(CodeModeResult("read", "A")), runtime.cell.results.last())
        assertEquals(1, runtime.starts)
    }

    @Test
    fun `failed sequential acceptance retries exposure then delivers both results once`() = runTest {
        val runtime = scripted(
            CodeModeStep.Calls(listOf(call("read", "Read"), call("edit", "Edit"))),
            CodeModeStep.Completed("done"),
        )
        val manager = bridge(runtime)
        val first = RecordingSink()
        manager.interceptor(turn(), disableParallel = true).intercept(BASE_REQUEST, first) { outerOutcome() }
        val firstId = first.tools.single().id
        val state = tempDir.resolve("bridge.json")
        Files.delete(state)
        Files.createDirectory(state)
        val failed = manager.interceptor(turn(firstId, "A"), disableParallel = true)
            .intercept(requestWithResult(firstId, "A"), RecordingSink()) { error("must not post") }
        assertTrue(failed is TurnOutcome.Failure)
        Files.delete(state)
        val next = RecordingSink()
        manager.interceptor(turn(firstId, "A"), disableParallel = true)
            .intercept(requestWithResult(firstId, "A"), next) { error("must not post") }
        val secondId = next.tools.single().id
        assertEquals("Edit", next.tools.single().name)
        val results = listOf(CodeModeResult(firstId, "A"), CodeModeResult(secondId, "B"))
        manager.interceptor(turn(results = results), disableParallel = true)
            .intercept(requestWithTwoResults(firstId, secondId), RecordingSink()) { completedOutcome() }
        assertEquals(listOf(CodeModeResult("read", "A"), CodeModeResult("edit", "B")), runtime.cell.results.last())
        assertEquals(2, runtime.cell.advances)
        assertEquals(1, runtime.starts)
    }

    @Test
    fun `passive sibling interruption preserves exact tool error evidence`() = runTest {
        val runtime = scripted(
            CodeModeStep.Calls(listOf(call("read", "Read"))),
            CodeModeStep.Calls(listOf(call("edit", "Edit"))),
        )
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) { outerOutcome() }
        val id = sink.tools.single().id
        val value = "evidence with \"quotes\"\nand Unicode é"
        val body = siblingBody(id, value)
        var upstream = ""
        val returned = turn(results = listOf(CodeModeResult(id, value, true)))
        val result = manager.interceptor(returned, disableParallel = false)
            .intercept(body, RecordingSink()) {
                upstream = it
                completedOutcome()
            }
        assertTrue(result is TurnOutcome.Success)
        assertEvidence(upstream, id, value)
        assertTrue("new user instruction" in upstream)
        assertEquals(1, runtime.starts)
    }

    @Test
    fun `lost worker continuation preserves newly returned evidence without rerunning source`() = runTest {
        val runtime = scripted(CodeModeStep.Calls(listOf(call("read", "Read"))))
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) { outerOutcome() }
        val id = sink.tools.single().id
        manager.onHeadStop()
        val replacement = ScriptedRuntime(ArrayDeque())
        val restored = bridge(replacement)
        val value = "completed before restart"
        var upstream = ""
        val returned = turn(results = listOf(CodeModeResult(id, value, true)))
        val result = restored.interceptor(returned, disableParallel = false)
            .intercept(siblingBody(id, value), RecordingSink()) {
                upstream = it
                completedOutcome()
            }
        assertTrue(result is TurnOutcome.Success)
        assertEvidence(upstream, id, value)
        assertTrue("new user instruction" in upstream)
        assertEquals(0, replacement.starts)
    }

    private fun scripted(vararg steps: CodeModeStep) = ScriptedRuntime(ArrayDeque(steps.toList()))

    private fun assertEvidence(body: String, id: String, value: String) {
        val items = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
        assertFalse(items.any { it.jsonObject["type"] == JsonPrimitive("function_call_output") })
        val output = items.single { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
            .jsonObject.getValue("output").jsonPrimitive.content
        val evidence = Json.parseToJsonElement(output).jsonObject
        assertEquals("interrupted", evidence.getValue("status").jsonPrimitive.content)
        assertEquals("false", evidence.getValue("sourceRerun").jsonPrimitive.content)
        val result = evidence.getValue("results").jsonArray.single().jsonObject
        assertEquals(id, result.getValue("id").jsonPrimitive.content)
        assertEquals(value, result.getValue("output").jsonPrimitive.content)
        assertEquals("true", result.getValue("isError").jsonPrimitive.content)
    }

    private fun siblingBody(id: String, value: String): String {
        val raw = Json.parseToJsonElement(requestWithSiblingBeforeResult(id, "new user instruction")).jsonObject
        val input = raw.getValue("input").jsonArray.map { element ->
            val item = element.jsonObject
            if (item["type"] == JsonPrimitive("function_call_output")) {
                JsonObject(item + ("output" to JsonPrimitive(value)))
            } else {
                item
            }
        }
        return JsonObject(raw + ("input" to JsonArray(input))).toString()
    }
}
