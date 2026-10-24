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
import kotlinx.serialization.json.JsonObject
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
import splice.core.turn.RoundText
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep

private const val DEVELOPER = """{"role":"developer","content":"s"}"""
private const val ABANDONED = "abandoned record"
private const val PREFACE = "Preface 0. "

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

    @Test
    fun `a script whose copy follows another one's in the same reply is sent once after a restart`() = runTest {
        val steps = listOf(CodeModeStep.Completed("first"), CodeModeStep.Completed("second"))
        val manager = bridge(ScriptedRuntime(ArrayDeque(steps)))
        val replies = ArrayDeque(
            listOf(quietOutcome("outer-1", "rs_first"), quietOutcome("outer-2", "rs_second"), completedOutcome()),
        )
        manager.interceptor(turn(), disableParallel = false)
            .intercept(body(conversation()), RecordingSink()) { RoundResult.Outcome(replies.removeFirst()) }
        // The first script is no longer one splice sends (abandoned, as a store an older splice wrote can hold),
        // yet the client keeps its reasoning ahead of the second script's.
        val key = stateFiles.records().first().getValue("key").jsonPrimitive.content
        val first = manager.registry.recordsFor(key).single { it.origin.outerCallId == "outer-1" }
        manager.registry.changes.save(first) { it.error = "$CODE_MODE_ABANDONED: synthetic; source was not rerun" }

        val restored = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("must not run")))))
        var posted = ""
        val next = conversation() + reasoning("rs_first") + reasoning("rs_second") + assistant("Done.") + user("Next.")
        restored.interceptor(turn(), disableParallel = false).intercept(body(next), RecordingSink()) {
            posted = it
            RoundResult.Outcome(completedOutcome())
        }

        val input = Json.parseToJsonElement(posted).jsonObject.getValue("input").jsonArray.map { it.jsonObject }
        fun count(type: String, field: String, id: String) =
            input.count { it["type"]?.jsonPrimitive?.content == type && it[field]?.jsonPrimitive?.content == id }
        assertEquals(1, count("reasoning", "id", "rs_first"), "the first script's reasoning, once: $posted")
        assertEquals(1, count("reasoning", "id", "rs_second"), "the second script's reasoning, once: $posted")
        assertEquals(1, count("custom_tool_call", "call_id", "outer-2"), "the second exec call, once: $posted")
        assertEquals(1, count("custom_tool_call_output", "call_id", "outer-2"), "its output, once: $posted")
        assertEquals("reasoning:rs_second", beforeCall(posted, "outer-2").last(), "its reasoning, then its call")
    }

    @Test
    fun `a script whose cut reply was retried keeps its place when a later script repeats its preface`() = runTest {
        // GPT ran the first script, with only a preface, on a reply a restart cut; Claude Code sent the same request
        // again, and GPT ran a second script, reasoning first, then the same preface.
        val first = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("first")))))
        val cut = ArrayDeque(listOf(prefaced("outer-1", PREFACE, reasoning = null), completedOutcome()))
        first.interceptor(turn(), disableParallel = false)
            .intercept(body(conversation()), RecordingSink()) { RoundResult.Outcome(cut.removeFirst()) }
        first.onProviderStop()

        val retried = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("second")))))
        val replies = ArrayDeque(listOf(prefaced("outer-2", PREFACE, reasoning = "rs_second"), completedOutcome()))
        val posts = mutableListOf<String>()
        retried.interceptor(turn(), disableParallel = false).intercept(body(conversation()), RecordingSink()) {
            posts += it
            RoundResult.Outcome(replies.removeFirst())
        }
        retried.onProviderStop()

        // The client kept the second reply only, and splice restarts once more before its next request.
        val restored = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("must not run")))))
        var posted = ""
        val next = conversation() + reasoning("rs_second") + assistant(PREFACE) + assistant("Done.") + user("Next.")
        restored.interceptor(turn(), disableParallel = false).intercept(body(next), RecordingSink()) {
            posted = it
            RoundResult.Outcome(completedOutcome())
        }

        val last = inputOf(posts.last())
        assertEquals(last, inputOf(posted).take(last.size), "GPT's next post extends its last one: $posted")
        val calls = inputOf(posted).filter { it["type"]?.jsonPrimitive?.content == "custom_tool_call" }
        assertEquals(listOf("outer-1", "outer-2"), calls.map { it.getValue("call_id").jsonPrimitive.content })
    }

    @Test
    fun `a script an older build saved goes after the copy of one this build saved before it`() = runTest {
        val steps = listOf(CodeModeStep.Completed("1"), CodeModeStep.Completed("2"))
        val first = bridge(ScriptedRuntime(ArrayDeque(steps)))
        val replies = ArrayDeque(
            listOf(
                prefaced("outer-1", PREFACE, reasoning = null),
                completedOutcome(),
                prefaced("outer-2", PREFACE, reasoning = null),
                completedOutcome(),
            ),
        )
        first.interceptor(turn(), disableParallel = false)
            .intercept(body(conversation()), RecordingSink()) { RoundResult.Outcome(replies.removeFirst()) }
        val answered = conversation() + assistant(PREFACE) + assistant("Done.") + user("Next.")
        first.interceptor(turn(), disableParallel = false)
            .intercept(body(answered), RecordingSink()) { RoundResult.Outcome(replies.removeFirst()) }
        first.onProviderStop()
        // The client never kept the second reply, and the operator rolled back to a build that saved its record
        // with no client start; Claude Code sends the same request again.
        olderBuild("outer-2")

        val restored = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("must not run")))))
        var posted = ""
        restored.interceptor(turn(), disableParallel = false).intercept(body(answered), RecordingSink()) {
            posted = it
            RoundResult.Outcome(completedOutcome())
        }

        val shown = listOf("assistant:$PREFACE", "exec:outer-1", "output:outer-1", "assistant:Done.", "user:Next.") +
            listOf("assistant:$PREFACE", "exec:outer-2", "output:outer-2")
        assertEquals(shown, shape(posted).drop(conversation().size), "each script where it ran: $posted")
    }

    @Test
    fun `a script an older build saved takes no preface of the client-calling script after it`() = runTest {
        // GPT ran the first script, with only a preface, on a reply a restart cut, and an older build saved it.
        val first = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("first")))))
        val cut = ArrayDeque(listOf(prefaced("outer-1", PREFACE, reasoning = null), completedOutcome()))
        first.interceptor(turn(), disableParallel = false)
            .intercept(body(conversation()), RecordingSink()) { RoundResult.Outcome(cut.removeFirst()) }
        first.onProviderStop()
        olderBuild("outer-1")

        // Claude Code sent the same request again. GPT ran a second script, reasoning first and then the same
        // preface, which read a file; this session shows the client no reasoning, so it keeps only the preface.
        val steps = listOf(CodeModeStep.Calls(listOf(call("r", "Read"))), CodeModeStep.Completed("second"))
        val retried = bridge(ScriptedRuntime(ArrayDeque(steps)))
        val sink = RecordingSink()
        retried.interceptor(turn(), disableParallel = false).intercept(body(conversation()), sink) {
            RoundResult.Outcome(prefaced("outer-2", PREFACE, reasoning = "rs_second"))
        }
        val read1 = sink.tools.single().id
        val answered = conversation() + assistant(PREFACE) + read(read1) + output(read1, "A")
        retried.interceptor(turn(read1, "A"), disableParallel = false)
            .intercept(body(answered), RecordingSink()) { RoundResult.Outcome(completedOutcome()) }
        retried.onProviderStop()

        val restored = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("must not run")))))
        var posted = ""
        restored.interceptor(turn(), disableParallel = false)
            .intercept(body(answered + assistant("Done.") + user("Next.")), RecordingSink()) {
                posted = it
                RoundResult.Outcome(completedOutcome())
            }

        // The client holds the second script's preface right before its call; the first script's was never kept.
        val shown = shape(posted)
        assertEquals(1, shown.count { it == "exec:outer-2" }, "the second exec call, once: $posted")
        assertEquals("assistant:$PREFACE", shown[shown.indexOf("exec:outer-2") - 1], "its preface first: $posted")
        assertEquals(listOf("assistant:Done.", "user:Next."), shown.takeLast(2), "then the client's reply: $posted")
    }

    /** The record of the script [callId] started, as a build that keeps no client start saved it. */
    private fun olderBuild(callId: String) = stateFiles.rewriteRecords { record ->
        val anchors = record["replayAnchors"] as? JsonObject
        if (anchors == null || "\"$callId\"" !in record.toString()) {
            record
        } else {
            JsonObject(record + ("replayAnchors" to JsonObject(anchors - "client")))
        }
    }

    /** Each item of the posted input as kind:what, the exec calls and outputs by call id. */
    private fun shape(bodyJson: String): List<String> = inputOf(bodyJson).map { item ->
        fun field(name: String) = item[name]?.jsonPrimitive?.content.orEmpty()
        when (field("type")) {
            "reasoning" -> "reasoning:${field("id")}"
            "custom_tool_call" -> "exec:${field("call_id")}"
            "custom_tool_call_output" -> "output:${field("call_id")}"
            "function_call" -> "call:${field("call_id")}"
            "function_call_output" -> "result:${field("call_id")}"
            else -> "${field("role")}:${field("content")}"
        }
    }

    private fun inputOf(bodyJson: String) =
        Json.parseToJsonElement(bodyJson).jsonObject.getValue("input").jsonArray.map { it.jsonObject }

    /** GPT's answer that starts a script after [preface], with the [reasoning] it gave first, if any. */
    private fun prefaced(callId: String, preface: String, reasoning: String?) = outerOutcome(callId).run {
        copy(
            handoffs = handoffs.copy(reasoningEnvelopes = listOfNotNull(reasoning?.let(::envelope))),
            text = RoundText(bodyText = preface, emittedText = true),
        )
    }

    /** GPT's answer that starts a script, with the reasoning it gave first. */
    private fun quietOutcome(callId: String, reasoning: String) = outerOutcome(callId).run {
        copy(handoffs = handoffs.copy(reasoningEnvelopes = listOf(envelope(reasoning))))
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
