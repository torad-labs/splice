import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import splice.core.turn.TurnOutcome
import splice.spi.CodeModeResult
import splice.spi.CodeModeStep

class CodexCodeModeActiveInterruptionTest : CodeModeBridgeTestSupport() {
    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `additional content interrupts incomplete active batches without advancing`(
        sequential: Boolean,
        partial: Boolean,
    ) =
        runTest {
            val runtime = ScriptedRuntime(
                ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"), call("edit", "Edit"))))),
            )
            val manager = bridge(runtime)
            val sink = RecordingSink()
            manager.interceptor(turn(), disableParallel = sequential).intercept(BASE_REQUEST, sink) { outerOutcome() }
            val id = sink.tools.first().id
            val results = if (partial) listOf(CodeModeResult(id, "evidence", true)) else emptyList()
            val original = Json.parseToJsonElement(requestWithSiblingBeforeResult(id, "new instruction")).jsonObject
            val input = original.getValue("input").jsonArray.mapNotNull { element ->
                val item = element.jsonObject
                if (item["type"] == JsonPrimitive("function_call_output")) {
                    if (partial) JsonObject(item + ("output" to JsonPrimitive("evidence"))) else null
                } else {
                    item
                }
            }
            val body = JsonObject(original + ("input" to JsonArray(input))).toString()
            var upstream = ""
            val outcome = manager.interceptor(turn(results = results), disableParallel = sequential)
                .intercept(body, RecordingSink()) {
                    upstream = it
                    completedOutcome()
                }
            assertTrue(outcome is TurnOutcome.Success, outcome.toString())
            assertEquals(1, runtime.cell.advances, "interruption must not advance JavaScript")
            assertTrue(runtime.cell.closed)
            assertTrue("new instruction" in upstream)
            val output = Json.parseToJsonElement(upstream).jsonObject.getValue("input").jsonArray
                .single { it.jsonObject["type"] == JsonPrimitive("custom_tool_call_output") }
                .jsonObject.getValue("output").jsonPrimitive.content
            val evidence = Json.parseToJsonElement(output).jsonObject
            assertEquals(if (partial) 1 else 0, evidence.getValue("results").jsonArray.size)
            assertEquals(if (partial) 1 else 2, evidence.getValue("unresolved").jsonArray.size)
            if (partial) {
                val result = evidence.getValue("results").jsonArray.single().jsonObject
                assertEquals("evidence", result.getValue("output").jsonPrimitive.content)
                assertEquals("true", result.getValue("isError").jsonPrimitive.content)
            }
        }
}
