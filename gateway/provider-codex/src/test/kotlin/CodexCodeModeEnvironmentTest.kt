import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.spi.CodeModeStep
import java.nio.file.Files

/**
 * The environment around a conversation moves while the conversation stands still: Claude Code grows
 * its tool list (ToolSearch loading a deferred schema, an MCP reconnect) and its system prompt
 * changes. Live on 2026-09-07 two sessions went 35 -> 36 eager tools one turn after a completed
 * script and every later turn was refused. These pin the contract: records measure the conversation
 * only, and history that no longer places a record degrades to the client's own history.
 */
class CodexCodeModeEnvironmentTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a tool list that grows after the script turn still resumes and rewrites the history`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("runtime-read", "Read"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), null, disableParallel = false)
            .intercept(liteBody(tools = listOf("Read"), instructions = "s"), sink) { outerOutcome() }
        val readId = sink.tools.single().id

        var posted = ""
        val outcome = manager.interceptor(turn(readId, "A"), null, disableParallel = false)
            .intercept(liteBody(listOf("Read", "Edit"), "s", callback(readId)), RecordingSink()) { body ->
                posted = body
                completedOutcome()
            }

        assertTrue(outcome is TurnOutcome.Success)
        // A grown tool list is a RESUME, not an interruption: the cell received the result.
        assertEquals(2, runtime.cell.advances)
        assertEquals("A", runtime.cell.results.last().single().output)
        val items = Json.parseToJsonElement(posted).jsonObject.getValue("input").jsonArray
        assertEquals(2, items[0].jsonObject.getValue("tools").jsonArray.size)
        assertEquals(listOf("user", "custom_tool_call", "custom_tool_call_output"), shapes(items).drop(2))
        assertFalse(readId in posted)
        assertTrue(logLines.none { "skipped" in it || "abandoned" in it })
    }

    @Test
    fun `instructions that change after the script turn still place the record`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("runtime-read", "Read"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), null, disableParallel = false)
            .intercept(liteBody(listOf("Read"), "first prompt"), sink) { outerOutcome() }
        val readId = sink.tools.single().id

        var posted = ""
        manager.interceptor(turn(readId, "A"), null, disableParallel = false)
            .intercept(liteBody(listOf("Read"), "second prompt", callback(readId)), RecordingSink()) { body ->
                posted = body
                completedOutcome()
            }

        val items = Json.parseToJsonElement(posted).jsonObject.getValue("input").jsonArray
        assertEquals("second prompt", items[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(listOf("user", "custom_tool_call", "custom_tool_call_output"), shapes(items).drop(2))
        assertFalse(readId in posted)
    }

    @Test
    fun `a completed record whose metadata predates v3 is omitted from the rewrite and logged`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("runtime-read", "Read"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), null, disableParallel = false)
            .intercept(liteBody(listOf("Read"), "s"), sink) { outerOutcome() }
        val readId = sink.tools.single().id
        manager.interceptor(turn(readId, "A"), null, disableParallel = false)
            .intercept(liteBody(listOf("Read"), "s", callback(readId)), RecordingSink()) { completedOutcome() }
        val stateFile = tempDir.resolve("bridge.json")
        val persisted = Files.readString(stateFile)
        Files.writeString(stateFile, persisted.replace("\"metadataVersion\":3", "\"metadataVersion\":2"))

        val restored = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("must not run")))))
        var posted = ""
        val outcome = restored.interceptor(turn(readId, "A"), null, disableParallel = false)
            .intercept(liteBody(listOf("Read"), "s", callback(readId)), RecordingSink()) { body ->
                posted = body
                completedOutcome()
            }

        assertTrue(outcome is TurnOutcome.Success)
        assertTrue(readId in posted)
        assertFalse("outer-call" in posted)
        assertTrue(logLines.any { "history rewrite skipped record" in it && "metadata is unavailable" in it })
    }

    @Test
    fun `a model switch keeps the conversation alive on the client history`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("runtime-read", "Read"))))))
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), null, disableParallel = false)
            .intercept(liteBody(listOf("Read"), "s"), sink) { outerOutcome() }
        val readId = sink.tools.single().id

        var posted = ""
        val outcome = manager.interceptor(turn(readId, "A", model = "gpt-6-sol"), null, disableParallel = false)
            .intercept(liteBody(listOf("Read"), "s", callback(readId)), RecordingSink()) { body ->
                posted = body
                completedOutcome()
            }

        assertTrue(outcome is TurnOutcome.Success)
        assertTrue(readId in posted)
        assertEquals(1, runtime.cell.advances)
        assertTrue(logLines.any { "belong to another session or model" in it })
    }

    private fun liteBody(tools: List<String>, instructions: String, history: String = ""): String {
        val toolItems = tools.joinToString(",") { """{"type":"function","name":"$it"}""" }
        return """{"input":[{"type":"additional_tools","role":"developer","tools":[$toolItems]},""" +
            """{"role":"developer","content":"$instructions"},{"role":"user","content":"start"}$history]}"""
    }

    private fun callback(id: String): String =
        """,{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"},""" +
            """{"type":"function_call_output","call_id":"$id","output":"A"}"""

    private fun shapes(items: JsonArray): List<String> = items.map { item ->
        item.jsonObject["type"]?.jsonPrimitive?.content ?: item.jsonObject.getValue("role").jsonPrimitive.content
    }
}
