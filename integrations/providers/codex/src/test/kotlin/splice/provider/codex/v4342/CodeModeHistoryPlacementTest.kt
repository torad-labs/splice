// NEW: V4-342 — nothing splice writes moves a parked script's history under it. Two of the three live
// abandons on 2026-09-26 (one gpt-5.6-sol subagent, records 8cbd23d8 and 48c27b3f) were splice's own
// rewrites. (1) A completed record's rewrite put its continuity reasoning ahead of the native tool
// search it made before its script, at the same offset, whenever the client did not replay the
// search (it never does): the next script, started in that record's live continuation where the order
// was still upstream's, found [rB, rA, search] where it recorded [rA, search][rB] and was abandoned
// as "native discovery history was edited". (2) A record abandoned in the request that started the
// next script was rewritten to its canonical output on the following request, under the new script's
// baseline, which had recorded its raw callbacks: abandoned again, as "logical history does not match".
package splice.provider.codex.v4342

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.reasoning.ReasoningReplay
import splice.provider.codex.CodeModeBridgeTestSupport
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.codemode.CodeModeStep

private const val DEVELOPER = """{"role":"developer","content":"s"}"""
private const val ABANDONED = "abandoned record"

/** The native tool search the model made before its first script, and what it found. */
private const val SEARCH =
    """{"type":"tool_search_call","call_id":"search-1","execution":"client","arguments":"{\"query\":\"Read\"}"}"""
private const val FOUND = """{"type":"tool_search_output","call_id":"search-1","status":"completed",""" +
    """"execution":"client","tools":[{"type":"function","name":"Read"}]}"""

class CodeModeHistoryPlacementTest : CodeModeBridgeTestSupport() {

    @Test
    fun `a script started in the continuation of one that searched first resumes on the next turn - V4-342`() =
        runTest {
            val runtime = ScriptedRuntime(
                ArrayDeque(
                    listOf(
                        CodeModeStep.Calls(listOf(call("runtime-read-1", "Read"))),
                        CodeModeStep.Completed("first"),
                        CodeModeStep.Calls(listOf(call("runtime-read-2", "Read"))),
                        CodeModeStep.Completed("second"),
                    ),
                ),
            )
            val manager = bridge(runtime)
            val opening = listOf(DEVELOPER, user("Find the reader and use it."), reasoning("rs_a"), SEARCH, FOUND)
            val first = RecordingSink()
            manager.interceptor(turn(), disableParallel = false).intercept(body(opening), first) {
                outerOutcome("outer-1").copy(reasoningEnvelopes = listOf(envelope("rs_b")))
            }
            val read1 = first.tools.single().id

            val second = RecordingSink()
            val answered1 = listOf(DEVELOPER, user("Find the reader and use it."), read(read1), output(read1, "A"))
            manager.interceptor(turn(read1, "A"), disableParallel = false).intercept(body(answered1), second) {
                outerOutcome("outer-2")
            }
            val read2 = second.tools.single().id

            var posted = ""
            manager.interceptor(turn(read2, "B"), disableParallel = false)
                .intercept(body(answered1 + read(read2) + output(read2, "B")), RecordingSink()) {
                    posted = it
                    completedOutcome()
                }

            assertTrue(logLines.none { ABANDONED in it }, logLines.joinToString("\n"))
            assertEquals(4, runtime.cell.advances, "the second script got its result")
            // V4-388: the search leaves the wire (exec declares no tool_search, as codex declares none);
            // the reasoning around it keeps upstream's order.
            assertEquals(
                listOf("reasoning:rs_a", "reasoning:rs_b"),
                beforeCall(posted, "outer-1"),
                "the first record's reasoning, in the order upstream produced it",
            )
        }

    @Test
    fun `a record abandoned in the request that starts the next script is never rewritten under it - V4-342`() =
        runTest {
            val runtime = ScriptedRuntime(
                ArrayDeque(
                    listOf(
                        CodeModeStep.Calls(listOf(call("runtime-read-1", "Read"))),
                        CodeModeStep.Calls(listOf(call("runtime-read-2", "Read"))),
                        CodeModeStep.Completed("second"),
                    ),
                ),
            )
            val manager = bridge(runtime)
            val read1 = startFirst(manager)
            val second = RecordingSink()
            manager.interceptor(turn(read1, "A"), disableParallel = false)
                .intercept(body(abandoning(read1)), second) { outerOutcome("outer-2") }
            val read2 = second.tools.single().id

            var posted = ""
            manager.interceptor(turn(read2, "B"), disableParallel = false)
                .intercept(body(history(read1) + read(read2) + output(read2, "B")), RecordingSink()) {
                    posted = it
                    completedOutcome()
                }

            assertEquals(1, logLines.count { ABANDONED in it }, "only the first record: ${logLines.joinToString("\n")}")
            assertEquals(3, runtime.cell.advances, "the second script got its result")
            assertTrue(read1 in posted, "the abandoned record's callback stays the client's")
            assertFalse("outer-1" in posted, "the abandoned record is never rewritten to its canonical output")
        }

    @Test
    fun `a record abandoned before a restart, read back from its store, is never rewritten - V4-342`() = runTest {
        val manager = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("r", "Read")))))))
        val read1 = startFirst(manager)
        manager.interceptor(turn(read1, "A"), disableParallel = false)
            .intercept(body(abandoning(read1)), RecordingSink()) { completedOutcome() }
        val stored = stateFiles.text()
        assertTrue("code-mode history no longer places the running script" in stored, "the store keeps the detail")

        val restored = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("must not run")))))
        var posted = ""
        restored.interceptor(turn(), disableParallel = false)
            .intercept(body(history(read1) + user("Next.")), RecordingSink()) {
                posted = it
                completedOutcome()
            }

        assertTrue(read1 in posted, "the abandoned record's callback stays the client's")
        assertFalse("outer-1" in posted, "an abandoned record read back from the store is never rewritten")
    }

    /** A script started on a plain exchange; the one client call id it exposed. */
    private suspend fun startFirst(manager: CodexCodeModeBridge): String {
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(body(conversation()), sink) {
            outerOutcome("outer-1")
        }
        return sink.tools.single().id
    }

    private fun conversation() = listOf(DEVELOPER, user("Fix it."), assistant("Looking."), user("Go on."))

    /** The conversation and the first script's callback, as the client sends it. */
    private fun history(read1: String) = conversation() + read(read1) + output(read1, "A")

    /** [history] with a reasoning item the record never saw in its baseline, before a message: the
     *  "native discovery history was edited" abandon, gone again on the next request. */
    private fun abandoning(read1: String) =
        listOf(DEVELOPER, user("Fix it."), reasoning("rs_once"), assistant("Looking."), user("Go on."), read(read1)) +
            output(read1, "A")

    /** Each item before [callId]'s custom_tool_call back to the previous logical item, as type:id. */
    private fun beforeCall(bodyJson: String, callId: String): List<String> {
        val input = Json.parseToJsonElement(bodyJson).jsonObject.getValue("input").jsonArray.map { it.jsonObject }
        val at = input.indexOfFirst {
            it["type"]?.jsonPrimitive?.content == "custom_tool_call" && it["call_id"]?.jsonPrimitive?.content == callId
        }
        val replay = setOf("reasoning", "tool_search_call", "tool_search_output")
        return input.subList(0, at).takeLastWhile { it["type"]?.jsonPrimitive?.content in replay }.map {
            val type = it.getValue("type").jsonPrimitive.content
            "$type:${if (type == "reasoning") it.getValue("id").jsonPrimitive.content else ""}"
        }
    }

    private fun body(items: List<String>) = """{"input":[${items.joinToString(",")}]}"""

    private fun user(text: String) = """{"role":"user","content":"$text"}"""

    private fun assistant(text: String) = """{"role":"assistant","phase":"commentary","content":"$text"}"""

    private fun reasoning(id: String) = """{"type":"reasoning","id":"$id","summary":[],"encrypted_content":"e-$id"}"""

    private fun read(id: String) = """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}"""

    private fun output(id: String, text: String) =
        """{"type":"function_call_output","call_id":"$id","output":"$text"}"""

    private fun envelope(id: String): String = checkNotNull(
        ReasoningReplay.encodeReasoningEnvelope(
            buildJsonObject {
                put("type", "reasoning")
                put("id", id)
                put("encrypted_content", "e-$id")
            },
        ),
    )
}
