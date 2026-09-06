import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

class CodexCodeModeHistoryTest : CodeModeBridgeTestSupport() {
    @Test
    fun `native search history absent from callbacks is restored across cell resumes`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("runtime-read", "Read"))),
                    CodeModeStep.Calls(listOf(call("runtime-edit", "Edit"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val manager = bridge(runtime)
        val searchId = "search-call"
        val initialBody = nativeSearchBody(searchId)
        val readSink = RecordingSink()
        val started = manager.interceptor(turn(), null, disableParallel = false)
            .intercept(initialBody, readSink) { outerOutcome() }
        assertTrue((started as TurnOutcome.Success).hasToolUse)

        val readId = readSink.tools.single().id
        val editSink = RecordingSink()
        val resumed = manager.interceptor(turn(readId, "A"), null, disableParallel = false)
            .intercept(requestWithResult(readId, "A"), editSink) { error("upstream must not run") }
        assertTrue((resumed as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("Edit"), editSink.tools.map { it.name })

        val editId = editSink.tools.single().id
        val finalTurn = turn(
            results = listOf(CodeModeResult(readId, "A"), CodeModeResult(editId, "B")),
        )
        var finalPost = ""
        manager.interceptor(finalTurn, null, disableParallel = false)
            .intercept(requestWithTwoResults(readId, editId), RecordingSink()) { body ->
                finalPost = body
                completedOutcome()
            }

        val items = Json.parseToJsonElement(finalPost).jsonObject.getValue("input").jsonArray
        val searchItems = items.filter { item ->
            item.jsonObject["call_id"]?.jsonPrimitive?.content == searchId
        }
        assertEquals(
            listOf("tool_search_call", "tool_search_output"),
            searchItems.map { it.jsonObject.getValue("type").jsonPrimitive.content },
        )
        val outerIndex = items.indexOfFirst { item ->
            item.jsonObject["call_id"]?.jsonPrimitive?.content == "outer-call"
        }
        assertTrue(items.indexOf(searchItems.last()) < outerIndex)
        assertFalse(readId in finalPost)
        assertFalse(editId in finalPost)
    }

    @Test
    fun `unexpected native replay at another baseline offset is rejected`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("runtime-read", "Read"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val manager = bridge(runtime)
        val readSink = RecordingSink()
        manager.interceptor(turn(), null, disableParallel = false)
            .intercept(baselineWithNativeSearch(), readSink) { outerOutcome() }
        val readId = readSink.tools.single().id
        var upstreamCalls = 0

        val outcome = manager.interceptor(turn(readId, "A"), null, disableParallel = false)
            .intercept(callbackWithUnexpectedSearch(readId), RecordingSink()) {
                upstreamCalls++
                completedOutcome()
            }

        assertTrue(outcome is TurnOutcome.Failure)
        assertEquals(0, upstreamCalls)
        assertEquals(1, runtime.cell.advances)
    }

    private fun nativeSearchBody(searchId: String): String =
        """
        {
          "input": [
            {"role":"developer","content":"s"},
            {
              "type":"tool_search_call",
              "call_id":"$searchId",
              "execution":"client",
              "arguments":"{\"query\":\"Read\"}"
            },
            {
              "type":"tool_search_output",
              "call_id":"$searchId",
              "status":"completed",
              "execution":"client",
              "tools":[{"type":"function","name":"Read"}]
            }
          ]
        }
        """.trimIndent()

    private fun baselineWithNativeSearch(): String =
        """
        {"input":[
          {"role":"developer","content":"s"},
          {"role":"user","content":"start"},
          {"type":"tool_search_call","call_id":"known-search","arguments":{"query":"release"}},
          {"type":"tool_search_output","call_id":"known-search","tools":[
            {"type":"function","name":"mcp__release__lookup"}
          ]}
        ]}
        """.trimIndent()

    private fun callbackWithUnexpectedSearch(readId: String): String =
        """
        {"input":[
          {"role":"developer","content":"s"},
          {"type":"tool_search_call","call_id":"unexpected-search","arguments":{"query":"edit"}},
          {"type":"tool_search_output","call_id":"unexpected-search","tools":[
            {"type":"function","name":"Edit"}
          ]},
          {"role":"user","content":"start"},
          {"type":"function_call","call_id":"$readId","name":"Read","arguments":"{}"},
          {"type":"function_call_output","call_id":"$readId","output":"A"}
        ]}
        """.trimIndent()
}
