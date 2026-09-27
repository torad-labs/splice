// NEW: V4-335 — the text a model says before it starts a splice_exec script is recorded as the same
// item the client replays for it. Since V4-335 a lite turn replays that preface as
// {role:assistant, phase:commentary, content}, and code mode recorded it as a bare {role, content}: the
// rewrite then found no continuity where the client put it, kept the client's copy AND re-inserted
// its own, and the unmatched copy counted as new client content that stopped the script (CI run
// 36279360319, the packaged mock's lookup-edit: "assistant continuity was lost, duplicated, or
// reordered"). A record written before continuity carried a phase is omitted, never placed.
package splice.provider.codex.v4335

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
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.LogSink
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.provider.codex.BASE_REQUEST
import splice.provider.codex.CodeModeBridgeTestSupport
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.CodexQuirks
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import java.nio.file.Files

private const val PREFACE = "I'll read the config first."
private const val MODEL = "gpt-6-astra"

class CodeModeContinuityPhaseTest : CodeModeBridgeTestSupport() {

    @Test
    fun `the preface code mode records is the item the client replays for it - V4-335`() {
        val recorded = CodexCodeModeWire(Json, LogSink {}).continuity(preface()).logicalItems.single()

        assertEquals(clientReplay().toString(), recorded.toString())
    }

    @Test
    fun `a finished script's preface is placed once and its result goes back to it - V4-335`() = runTest {
        val runtime = runtime()
        val manager = bridge(runtime)
        val read = finish(manager)

        val upstream = next(manager, read)

        assertEquals(2, runtime.cell.advances, "the replayed preface is the record's own, not new content")
        assertEquals(1, prefaces(upstream), upstream)
    }

    @Test
    fun `a finished record written before continuity carried a phase is omitted, not placed twice - V4-335`() =
        runTest {
            val read = finish(bridge(runtime()))
            writtenByV3()

            val upstream = next(bridge(runtime()), read)

            assertEquals(1, prefaces(upstream), upstream)
            assertTrue(
                logLines.any { "history rewrite skipped record" in it && "metadata is unavailable" in it },
                logLines.joinToString("\n"),
            )
        }

    /** A script that asks for one Read, then finishes. */
    private fun runtime() = ScriptedRuntime(
        ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read", "Read"))), CodeModeStep.Completed("done"))),
    )

    /** The model says [PREFACE] and starts the script; the client answers its Read. The Read's id. */
    private suspend fun finish(manager: CodexCodeModeBridge): String {
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) { preface() }
        val read = sink.tools.single().id
        manager.interceptor(turn(results = listOf(CodeModeResult(read, "A"))), disableParallel = false)
            .intercept(answered(read), RecordingSink()) { completedOutcome() }
        return read
    }

    /** The operator's next message, after the finished script: the body posted upstream. */
    private suspend fun next(manager: CodexCodeModeBridge, read: String): String {
        val body = Json.parseToJsonElement(answered(read)).jsonObject
        val input = body.getValue("input").jsonArray + buildJsonObject {
            put("role", "user")
            put("content", "Now the tests.")
        }
        var upstream = ""
        manager.interceptor(turn(), disableParallel = false)
            .intercept(JsonObject(body + ("input" to JsonArray(input))).toString(), RecordingSink()) {
                upstream = it
                completedOutcome()
            }
        return upstream
    }

    private fun preface() = TurnOutcome.Success(
        hasToolUse = false,
        incomplete = false,
        usage = Usage(),
        bodyText = PREFACE,
        emittedText = true,
        customCalls = listOf(outer()),
    )

    /** What the client sends once it has the Read's result: the preface as the builder replays it,
     *  then the call and its output. */
    private fun answered(read: String): String = """{"input":[{"role":"developer","content":"s"},""" +
        clientReplay() +
        """,{"type":"function_call","call_id":"$read","name":"Read","arguments":"{}"},""" +
        """{"type":"function_call_output","call_id":"$read","output":"A"}]}"""

    /** The lite builder's replay of an assistant message holding [PREFACE] and then a tool_use. */
    private fun clientReplay(): JsonObject {
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"claude-codex--$MODEL","max_tokens":100,"messages":[
                {"role":"user","content":"Fix the config."},
                {"role":"assistant","content":[{"type":"text","text":"$PREFACE"},
                  {"type":"tool_use","id":"toolu_1","name":"Read","input":{}}]}]}""",
        )
        val opts = BuildOptions(
            compact = false,
            originalModel = "claude-codex--$MODEL",
            upstreamModel = MODEL,
            configEffort = "high",
            configSummary = null,
            showReasoning = ReasoningDisplay.OFF,
            replayReasoning = InjectPriorReasoning(false),
            decodeReasoningEnvelope = { null },
        )
        val input = ResponsesRequestBuilder(CodexQuirks().defaultQuirks()).build(parsed.typed, parsed.raw, opts)
            .req.getValue("input").jsonArray
        return input.single { roleOf(it) == "assistant" }.jsonObject
    }

    /** The state file as a v3 daemon left it: the version it stamped, its continuity with no phase. */
    private fun writtenByV3() {
        val file = tempDir.resolve("bridge.json")
        val state = Json.parseToJsonElement(Files.readString(file)).jsonObject
        val records = state.getValue("records").jsonArray.map { record ->
            val continuity = record.jsonObject.getValue("continuity").jsonArray.map {
                JsonObject(it.jsonObject - "phase")
            }
            JsonObject(
                record.jsonObject + mapOf(
                    "metadataVersion" to JsonPrimitive(V3),
                    "continuity" to JsonArray(continuity),
                ),
            )
        }
        Files.writeString(file, JsonObject(state + ("records" to JsonArray(records))).toString())
    }

    private fun prefaces(body: String): Int =
        Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray.count { PREFACE in it.toString() }

    private fun roleOf(item: JsonElement): String? = (item.jsonObject["role"] as? JsonPrimitive)?.content
}

private const val V3 = 3
