import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.InjectPriorReasoning
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestBuilder
import splice.spi.CodeModeStep

class CodexCodeModeContinuityTest : CodeModeBridgeTestSupport() {
    @Test
    fun `builder assistant text is expected continuity during dependent resume`() = runTest {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(
                    CodeModeStep.Calls(listOf(call("runtime-read", "Read"))),
                    CodeModeStep.Calls(listOf(call("runtime-edit", "Edit"))),
                ),
            ),
        )
        val manager = bridge(runtime)
        val readSink = RecordingSink()
        val started = manager.interceptor(turn(), null, disableParallel = false)
            .intercept(responsesBody(INITIAL_MESSAGES), readSink) { textOuterOutcome("outer-a") }
        assertTrue((started as TurnOutcome.Success).hasToolUse)

        val readId = readSink.tools.single().id
        val editSink = RecordingSink()
        val resumed = manager.interceptor(turn(readId, "A"), null, disableParallel = false)
            .intercept(
                responsesBody(callbackMessages(readId, includeReasoning = false)),
                editSink,
            ) { error("upstream must not run") }

        assertTrue((resumed as TurnOutcome.Success).hasToolUse)
        assertEquals(listOf("Edit"), editSink.tools.map { it.name })
        assertEquals(2, runtime.cell.advances)
    }

    @Test
    fun `builder reasoning and text remain single and ordered through completed A then B`() = runTest {
        val runtime = QueuedRuntime(
            ArrayDeque(
                listOf(
                    ArrayDeque(listOf(CodeModeStep.Completed("one"))),
                    ArrayDeque(
                        listOf(
                            CodeModeStep.Calls(listOf(call("runtime-read", "Read"))),
                            CodeModeStep.Completed("two"),
                        ),
                    ),
                ),
            ),
        )
        val manager = bridge(runtime)
        val readSink = RecordingSink()
        var posts = 0
        val started = manager.interceptor(turn(), null, disableParallel = false)
            .intercept(responsesBody(INITIAL_MESSAGES), readSink) {
                posts++
                if (posts == 1) continuityOuterOutcome("outer-a") else outerOutcome("outer-b")
            }
        assertTrue((started as TurnOutcome.Success).hasToolUse)
        assertEquals(2, posts)

        val readId = readSink.tools.single().id
        var finalPost = ""
        val resumed = manager.interceptor(turn(readId, "A"), null, disableParallel = false)
            .intercept(
                responsesBody(callbackMessages(readId, includeReasoning = true), replayReasoning = true),
                RecordingSink(),
            ) { body ->
                finalPost = body
                completedOutcome()
            }

        assertTrue(resumed is TurnOutcome.Success)
        assertContinuityOrder(finalPost)
    }

    private fun responsesBody(messages: String, replayReasoning: Boolean = false): String {
        val raw = """
            {
              "model":"gpt-6-astra",
              "tools":[
                {"name":"Read","input_schema":{"type":"object"}},
                {"name":"Edit","input_schema":{"type":"object"}}
              ],
              "messages":[$messages]
            }
        """.trimIndent()
        val parsed = AnthropicParse.parseAnthropicBody(raw)
        return ResponsesRequestBuilder(ResponsesQuirks(providerTag = "test"))
            .build(parsed.typed, parsed.raw, buildOptions(replayReasoning)).req.toString()
    }

    private fun buildOptions(replayReasoning: Boolean) = BuildOptions(
        compact = false,
        originalModel = "gpt-6-astra",
        upstreamModel = "gpt-6-astra",
        configEffort = null,
        configSummary = null,
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = InjectPriorReasoning(replayReasoning),
        decodeReasoningEnvelope = { ReasoningReplay.decodeReasoningEnvelope(it) },
    )

    private fun callbackMessages(toolId: String, includeReasoning: Boolean): String {
        val reasoning = if (includeReasoning) {
            """{"type":"redacted_thinking","data":"${reasoningEnvelope()}"},"""
        } else {
            ""
        }
        return """
            {"role":"user","content":"start"},
            {"role":"assistant","content":[
              $reasoning
              {"type":"text","text":"$PREFACE"},
              {"type":"tool_use","id":"$toolId","name":"Read","input":{}}
            ]},
            {"role":"user","content":[
              {"type":"tool_result","tool_use_id":"$toolId","content":"A"}
            ]}
        """.trimIndent()
    }

    private fun textOuterOutcome(callId: String) = TurnOutcome.Success(
        false,
        false,
        Usage(inputTokens = 100),
        bodyText = PREFACE,
        emittedText = true,
        customCalls = listOf(outer(callId)),
    )

    private fun continuityOuterOutcome(callId: String) = textOuterOutcome(callId).copy(
        reasoningEnvelopes = listOf(reasoningEnvelope()),
    )

    private fun reasoningEnvelope(): String = checkNotNull(
        ReasoningReplay.encodeReasoningEnvelope(
            buildJsonObject {
                put("type", "reasoning")
                put("id", REASONING_ID)
                put("encrypted_content", "encrypted")
            },
        ),
    )

    private fun assertContinuityOrder(bodyJson: String) {
        val input = Json.parseToJsonElement(bodyJson).jsonObject.getValue("input").jsonArray
        val reasoning = input.filter { item ->
            item.jsonObject["id"]?.jsonPrimitive?.content == REASONING_ID
        }
        val assistant = input.filter { item ->
            item.jsonObject["content"]?.jsonPrimitive?.content == PREFACE
        }
        assertEquals(1, reasoning.size)
        assertEquals(1, assistant.size)
        val reasoningIndex = input.indexOf(reasoning.single())
        val assistantIndex = input.indexOf(assistant.single())
        val outerAIndex = input.indexOfFirst { "outer-a" in it.toString() }
        val outerBIndex = input.indexOfFirst { "outer-b" in it.toString() }
        assertTrue(reasoningIndex < assistantIndex)
        assertTrue(assistantIndex < outerAIndex)
        assertTrue(outerAIndex < outerBIndex)
    }

    private companion object {
        const val INITIAL_MESSAGES = """{"role":"user","content":"start"}"""
        const val PREFACE = "working through the request"
        const val REASONING_ID = "reasoning-a"
    }
}
