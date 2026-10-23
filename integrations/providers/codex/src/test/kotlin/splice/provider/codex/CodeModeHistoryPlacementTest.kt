// NEW: nothing splice writes moves a parked script's history under it. Two of the three live
// abandons on 2026-09-26 (one gpt-5.6-sol subagent, records 8cbd23d8 and 48c27b3f) were splice's own
// rewrites: a completed record's continuity put ahead of the native search it made before its script, and a
// record abandoned in one request rewritten under the next script's baseline. Since 2026-10-23 each request
// extends what GPT was last sent, the way Codex builds each request from what it recorded (codex-rs
// core/src/session/turn.rs:1618), so a script resumes on what it started on; an edit ahead of a running script
// falls back to the client's history once and the script keeps running, where it was abandoned before; and an
// abandoned record keeps the client's calls for good.
package splice.provider.codex

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
import splice.upstream.RoundResult
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
    fun `a script started in the continuation of one that searched first resumes on the next turn`() =
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
                RoundResult.Outcome(
                    outerOutcome("outer-1").run {
                        copy(handoffs = handoffs.copy(reasoningEnvelopes = listOf(envelope("rs_b"))))
                    },
                )
            }
            val read1 = first.tools.single().id

            val second = RecordingSink()
            // The client replays the reasoning it was shown, never the search it was not.
            val answered1 = listOf(DEVELOPER, user("Find the reader and use it."), reasoning("rs_a")) +
                reasoning("rs_b") + read(read1) + output(read1, "A")
            manager.interceptor(turn(read1, "A"), disableParallel = false).intercept(body(answered1), second) {
                RoundResult.Outcome(outerOutcome("outer-2"))
            }
            val read2 = second.tools.single().id

            var posted = ""
            manager.interceptor(turn(read2, "B"), disableParallel = false)
                .intercept(body(answered1 + read(read2) + output(read2, "B")), RecordingSink()) {
                    posted = it
                    RoundResult.Outcome(completedOutcome())
                }

            assertTrue(logLines.none { ABANDONED in it }, logLines.joinToString("\n"))
            assertEquals(4, runtime.cell.advances, "the second script got its result")
            // V4-388: the search leaves the wire (exec declares no tool_search, as codex declares none). The reasoning
            // is the client's replay, sent once and in the order upstream produced it.
            assertEquals(
                listOf("reasoning:rs_a", "reasoning:rs_b"),
                beforeCall(posted, "outer-1"),
                "the first record's reasoning, in the order upstream produced it",
            )
        }

    @Test
    fun `an edit ahead of a running script falls back once and the script keeps running`() =
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
                .intercept(body(abandoning(read1)), second) { error("the script asks its next call before any post") }
            val read2 = second.tools.single().id

            var posted = ""
            manager.interceptor(turn(read2, "B"), disableParallel = false)
                .intercept(body(history(read1) + read(read2) + output(read2, "B")), RecordingSink()) {
                    posted = it
                    RoundResult.Outcome(completedOutcome())
                }

            assertTrue(logLines.none { ABANDONED in it }, logLines.joinToString("\n"))
            assertEquals(3, runtime.cell.advances, "the script got both results")
            assertFalse(read1 in posted || read2 in posted, "a client call the script made went upstream: $posted")
            assertEquals(2, Regex("outer-1").findAll(posted).count(), "the exec call and its output: $posted")
            assertFalse("rs_once" in posted, "the edit the client took back went upstream: $posted")
        }

    @Test
    fun `a record abandoned before a restart, read back from its store, is never rewritten`() = runTest {
        val steps = listOf(CodeModeStep.Calls(listOf(call("r", "Read"))), CodeModeStep.Completed("done"))
        val manager = bridge(ScriptedRuntime(ArrayDeque(steps)))
        val read1 = startFirst(manager)
        manager.interceptor(turn(read1, "A"), disableParallel = false)
            .intercept(body(history(read1)), RecordingSink()) { RoundResult.Outcome(completedOutcome()) }
        // A store an older splice wrote, which abandoned the record and went on upstream on its client calls.
        val record = manager.registry.recordsFor(stateFiles.records().single().getValue("key").jsonPrimitive.content)
            .single()
        manager.registry.changes.save(record) { it.error = "$CODE_MODE_ABANDONED: synthetic; source was not rerun" }
        val stored = stateFiles.text()
        assertTrue("code-mode history no longer places the running script" in stored, "the store keeps the detail")

        val restored = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("must not run")))))
        var posted = ""
        restored.interceptor(turn(), disableParallel = false)
            .intercept(body(history(read1) + user("Next.")), RecordingSink()) {
                posted = it
                RoundResult.Outcome(completedOutcome())
            }

        assertTrue(read1 in posted, "the abandoned record's callback stays the client's")
        assertFalse("outer-1" in posted, "an abandoned record read back from the store is never rewritten")
    }

    /** A script started on a plain exchange; the one client call id it exposed. */
    private suspend fun startFirst(manager: CodexCodeModeBridge): String {
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(body(conversation()), sink) {
            RoundResult.Outcome(outerOutcome("outer-1"))
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
