// NEW: the text a model says before it starts a splice_exec script is recorded as the same
// item the client replays for it. Since V4-335 a lite turn replays that preface as
// {role:assistant, phase:commentary, content}, and code mode recorded it as a bare {role, content}: the
// rewrite then found no continuity where the client put it, kept the client's copy AND re-inserted
// its own, and the unmatched copy counted as new client content that stopped the script (CI run
// 36279360319, the packaged mock's lookup-edit: "assistant continuity was lost, duplicated, or
// reordered"). A record written before continuity carried a phase is omitted, never placed.
// a script that made no client call leaves its preface in a message with no tool_use, which
// the client replays as final_answer; the record's copy is still the same item, placed once, and its
// commentary is the truer phase: the preface did precede the splice_exec call upstream.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ResponseShape
import splice.core.turn.RoundHandoffs
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.LogSink
import splice.dialect.responses.buildResponsesTestRequest
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep

private const val PREFACE = "I'll read the config first."
private const val MODEL = "gpt-6-astra"
private const val ANSWER = "The timeout is 10 s."

class CodeModeContinuityPhaseTest : CodeModeBridgeTestSupport() {

    @Test
    fun `the preface code mode records is the item the client replays for it`() {
        val recorded = CodexCodeModeWire(Json, LogSink {}).continuity(preface()).logicalItems.single()

        assertEquals(clientReplay().toString(), recorded.toString())
    }

    @Test
    fun `a finished script's preface is placed once and its result goes back to it`() = runTest {
        val runtime = runtime()
        val manager = bridge(runtime)
        val read = finish(manager)

        val upstream = next(manager, answered(read))

        assertEquals(2, runtime.cell.advances, "the replayed preface is the record's own, not new content")
        assertEquals(1, prefaces(upstream), upstream)
    }

    @Test
    fun `a finished record written before continuity carried a phase is omitted, not placed twice`() =
        runTest {
            val read = finish(bridge(runtime()))
            writtenByV3()

            val upstream = next(bridge(runtime()), answered(read))

            assertEquals(1, prefaces(upstream), upstream)
            assertTrue(
                logLines.any { "history rewrite skipped record" in it && "metadata is unavailable" in it },
                logLines.joinToString("\n"),
            )
        }

    @Test
    fun `a script that made no client call has its preface placed once on the next turn`() = runTest {
        val manager = bridge(ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Completed("computed")))))
        var posts = 0
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, RecordingSink()) {
            posts++
            RoundResult.Outcome(if (posts == 1) preface() else answer())
        }
        val history = replayOf("""{"type":"text","text":"$PREFACE"},{"type":"text","text":"$ANSWER"}""")
        assertEquals(listOf("final_answer", "final_answer"), history.map { it.getValue("phase").toString().trim('"') })

        val upstream = next(manager, conversation(history))

        assertEquals(1, prefaces(upstream), upstream)
    }

    @Test
    fun `a preface the client replays as the answer is still the script's own, not new content`() =
        runTest {
            val runtime = runtime()
            val manager = bridge(runtime)
            val sink = RecordingSink()
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) {
                RoundResult.Outcome(preface())
            }
            val read = sink.tools.single().id
            val asAnswer = JsonObject(clientReplay() + ("phase" to JsonPrimitive("final_answer")))

            var upstream = ""
            manager.interceptor(turn(results = listOf(CodeModeResult(read, "A"))), disableParallel = false)
                .intercept(answered(read, asAnswer), RecordingSink()) {
                    upstream = it
                    RoundResult.Outcome(completedOutcome())
                }

            assertEquals(2, runtime.cell.advances, "the script got its result")
            assertEquals(1, prefaces(upstream), upstream)
        }

    /** A script that asks for one Read, then finishes. */
    private fun runtime() = ScriptedRuntime(
        ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"))), CodeModeStep.Completed("done"))),
    )

    /** The model says [PREFACE] and starts the script; the client answers its Read. The Read's id. */
    private suspend fun finish(manager: CodexCodeModeBridge): String {
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) {
            RoundResult.Outcome(preface())
        }
        val read = sink.tools.single().id
        manager.interceptor(turn(results = listOf(CodeModeResult(read, "A"))), disableParallel = false)
            .intercept(answered(read), RecordingSink()) { RoundResult.Outcome(completedOutcome()) }
        return read
    }

    /** The operator's next message after [history], the finished script's turn: the body posted upstream. */
    private suspend fun next(manager: CodexCodeModeBridge, history: String): String {
        val body = Json.parseToJsonElement(history).jsonObject
        val input = body.getValue("input").jsonArray + buildJsonObject {
            put("role", "user")
            put("content", "Now the tests.")
        }
        var upstream = ""
        manager.interceptor(turn(), disableParallel = false)
            .intercept(JsonObject(body + ("input" to JsonArray(input))).toString(), RecordingSink()) {
                upstream = it
                RoundResult.Outcome(completedOutcome())
            }
        return upstream
    }

    private fun preface() = TurnOutcome.Success(
        hasToolUse = false,
        incomplete = false,
        usage = Usage(),
        text = RoundText(bodyText = PREFACE, emittedText = true),
        handoffs = RoundHandoffs(customCalls = listOf(outer())),
    )

    private fun answer() = TurnOutcome.Success(
        hasToolUse = false,
        incomplete = false,
        usage = Usage(),
        text = RoundText(bodyText = ANSWER, emittedText = true),
        shape = ResponseShape(messageClosed = true),
    )

    /** What the client sends once it has the Read's result: [preface] (the builder's replay of it by
     *  default), then the call and its output. */
    private fun answered(read: String, preface: JsonObject = clientReplay()): String =
        """{"input":[{"role":"developer","content":"s"},""" + preface +
            """,{"type":"function_call","call_id":"$read","name":"Read","arguments":"{}"},""" +
            """{"type":"function_call_output","call_id":"$read","output":"A"}]}"""

    /** The client's body after the preamble: [items], as the builder replayed them. */
    private fun conversation(items: List<JsonObject>): String =
        """{"input":[{"role":"developer","content":"s"},${items.joinToString(",")}]}"""

    /** The lite builder's replay of an assistant message holding [PREFACE] and then a tool_use. */
    private fun clientReplay(): JsonObject = replayOf(
        """{"type":"text","text":"$PREFACE"},{"type":"tool_use","id":"toolu_1","name":"Read","input":{}}""",
    ).single()

    /** The lite builder's replay of an assistant message whose content is [blocks]: its assistant items. */
    private fun replayOf(blocks: String): List<JsonObject> {
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"claude-codex--$MODEL","max_tokens":100,"messages":[
                {"role":"user","content":"Fix the config."},
                {"role":"assistant","content":[$blocks]}]}""",
        )
        val input = buildResponsesTestRequest(CodexQuirks().defaultQuirks(), parsed, model = MODEL)
            .getValue("input").jsonArray
        return input.filter { roleOf(it) == "assistant" }.map { it.jsonObject }
    }

    /** The state file as a v3 daemon left it: the version it stamped, its continuity with no phase. */
    private fun writtenByV3() = stateFiles.rewriteRecords { record ->
        val continuity = record.getValue("continuity").jsonArray.map { JsonObject(it.jsonObject - "phase") }
        JsonObject(
            record + mapOf(
                "metadataVersion" to JsonPrimitive(V3),
                "continuity" to JsonArray(continuity),
            ),
        )
    }

    private fun prefaces(body: String): Int =
        Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray.count { PREFACE in it.toString() }

    private fun roleOf(item: JsonElement): String? = (item.jsonObject["role"] as? JsonPrimitive)?.content
}

private const val V3 = 3
