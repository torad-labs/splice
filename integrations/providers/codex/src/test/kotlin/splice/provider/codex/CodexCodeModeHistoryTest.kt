package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep

class CodexCodeModeHistoryTest : CodeModeBridgeTestSupport() {
    @Test
    fun `persisted input digest is byte stable across digest owner relocation`() {
        val codec = CodexCodeModeHistoryCodec(Json)
        val body = """{"input":[{"role":"developer","content":"preamble"},{"role":"user","content":"á"}]}"""
        val baseline = requireNotNull(codec.inputBoundary(body))
        assertEquals("1e5c5942b0178c373b12ed3d8fe82bae865430c7a8f0f98214b12fb997901608", baseline.fullDigest)
    }

    @Test
    fun `a completed A record cannot rewrite B's changed text result`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("read", "Read"))),
                    CodeModeStep.Completed("done"),
                ),
            ),
        )
        val manager = bridge(runtime)
        val first = RecordingSink()
        manager.interceptor(turn(), outer(), disableParallel = false)
            .intercept(BASE_REQUEST, first) { outerOutcome() }
        val id = first.tools.single().id
        manager.interceptor(turn(id, "X"), disableParallel = false)
            .intercept(requestWithResult(id, "X"), RecordingSink()) { completedOutcome() }
        val different = requestWithResult(id, "Y")
        var posted = ""
        val outcome = manager.interceptor(turn(id, "Y"), disableParallel = false)
            .intercept(different, RecordingSink()) { body ->
                posted = body
                completedOutcome()
            }

        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(Json.parseToJsonElement(different), Json.parseToJsonElement(posted))
        assertTrue((outcome as TurnOutcome.Success).usage.codeModeDiverged)
        assertEquals(2, runtime.cell.advances, "A's completed script was not rerun")
    }

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
        // V4-388: the search is restored in the client's history and kept off the wire — exec declares no
        // tool_search beside it, as codex declares none (CodeModeExecWireTest pins the strip itself).
        assertEquals(emptyList<JsonElement>(), searchItems)
        assertTrue(items.any { it.jsonObject["call_id"]?.jsonPrimitive?.content == "outer-call" })
        assertFalse(readId in finalPost)
        assertFalse(editId in finalPost)
    }

    @Test
    fun `unexpected native replay at another baseline offset abandons the script and continues upstream`() = runTest {
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

        // The running cell is closed without rerunning its source, and the turn goes upstream on
        // the client's history rather than failing the conversation.
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(1, upstreamCalls)
        assertEquals(1, runtime.cell.advances)
        assertTrue(runtime.cell.closed)
        assertTrue(logLines.any { "abandoned record" in it && "native discovery history was edited" in it })
    }

    @Test
    fun `an abandoned record retires with its evidence instead of being abandoned again every turn`() = runTest {
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
        repeat(3) {
            val outcome = manager.interceptor(turn(readId, "A"), null, disableParallel = false)
                .intercept(callbackWithUnexpectedSearch(readId), RecordingSink()) {
                    upstreamCalls++
                    completedOutcome()
                }
            assertTrue(outcome is TurnOutcome.Success)
        }
        // 2026-09-20: one LOST record was re-found by its client ids and re-abandoned on 82 turns.
        assertEquals(3, upstreamCalls)
        assertEquals(1, logLines.count { "abandoned record" in it })
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
