package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.parse.AnthropicParse
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.TurnScope
import splice.core.util.JsonScalars
import splice.upstream.BuiltTurn
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep

/** The bridge receives real typed callback blocks; flattened marker text alone is not clearing authority. */
class CodeModeClearedCallbackHistoryTest : CodeModeBridgeTestSupport() {
    private data class Echo(
        val id: String,
        val parts: String,
        val session: String = "session-a",
        val branch: String = "branch A",
        val duplicate: Boolean = false,
    )

    private data class Accepted(
        val manager: CodexCodeModeBridge,
        val id: String,
        val canonical: List<JsonObject>,
    )

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a verified cleared callback retains exact canonical exec output and reasoning`(restart: Boolean) = runTest {
        val accepted = accept(textPart("accepted contents"))
        val manager = if (restart) bridge(QueuedRuntime(ArrayDeque())) else accepted.manager
        val sent = followUp(manager, Echo(accepted.id, textPart(CLEARED_CALLBACK_MARKER)))
        assertTrue(accepted.canonical.any { JsonScalars.str(it, "type") == "reasoning" })
        assertTrue(accepted.canonical.any { JsonScalars.str(it, "type") == "custom_tool_call" })
        assertTrue(accepted.canonical.any { JsonScalars.str(it, "type") == "custom_tool_call_output" })
        assertEquals(accepted.canonical + item("""{"role":"user","content":"Continue."}"""), sent)
        assertFalse(sent.any { JsonScalars.str(it, "call_id") == accepted.id })
        assertFalse(sent.any { CLEARED_CALLBACK_MARKER in it.toString() })
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "text", "image", "marker-split", "marker-text", "marker-image",
            "branch", "session", "duplicate", "unaccepted",
        ],
    )
    fun `changed or unverifiable callbacks never recover another canonical script`(shape: String) = runTest {
        val accepted = accept(if (shape == "image") imageParts("AA==") else textPart("accepted contents"))
        val echo = changedEcho(accepted.id, shape)
        val sent = followUp(bridge(QueuedRuntime(ArrayDeque())), echo)
        assertFalse(
            sent.any { JsonScalars.str(it, "call_id") == "outer-cleared" },
            "$shape must not recover the old exec",
        )
        assertFalse(sent.any { JsonScalars.str(it, "id") == "rs_cleared" }, "$shape must not recover its reasoning")
        assertTrue(
            sent.any {
                JsonScalars.str(it, "type") == "function_call_output" && JsonScalars.str(it, "call_id") == echo.id
            },
        )
        if (shape == "image") {
            assertTrue(sent.any { "BB==" in it.toString() })
            assertFalse(sent.any { "AA==" in it.toString() })
        }
    }

    private fun changedEcho(id: String, shape: String): Echo = when (shape) {
        "text" -> Echo(id, textPart("different contents"))
        "image" -> Echo(id, imageParts("BB=="))
        "branch" -> Echo(id, textPart(CLEARED_CALLBACK_MARKER), branch = "branch B")
        "session" -> Echo(id, textPart(CLEARED_CALLBACK_MARKER), session = "session-b")
        "duplicate" -> Echo(id, textPart(CLEARED_CALLBACK_MARKER), duplicate = true)
        "unaccepted" -> Echo("toolu_splice_unaccepted", textPart(CLEARED_CALLBACK_MARKER))
        else -> Echo(id, augmentedMarker(shape))
    }

    private fun augmentedMarker(shape: String): String = when (shape) {
        "marker-split" -> textPart("[Old tool result ") + "," + textPart("content cleared]")
        "marker-text" -> textPart(CLEARED_CALLBACK_MARKER) + "," + textPart("suffix")
        "marker-image" -> textPart(CLEARED_CALLBACK_MARKER) + "," + imagePart("BB==")
        else -> error("unknown fixture shape")
    }

    private suspend fun accept(parts: String): Accepted {
        val runtime = ScriptedRuntime(
            ArrayDeque(
                listOf(CodeModeStep.Calls(listOf(call("read", "Read"))), CodeModeStep.Completed("script output")),
            ),
        )
        val manager = bridge(runtime)
        val first = prepare(manager)
        val sink = RecordingSink()
        val outcome = outerOutcome("outer-cleared").run {
            copy(handoffs = handoffs.copy(reasoningEnvelopes = listOf(reasoning())))
        }
        checkNotNull(
            first.roundInterceptor,
        ).intercept(first.requestBody.toString(), sink) { RoundResult.Outcome(outcome) }
        val id = sink.tools.single().id
        val answer = prepare(manager, Echo(id, parts))
        var posted = ""
        checkNotNull(answer.roundInterceptor).intercept(answer.requestBody.toString(), RecordingSink()) {
            posted = it
            RoundResult.Outcome(completedOutcome())
        }
        return Accepted(manager, id, items(posted))
    }

    private suspend fun followUp(manager: CodexCodeModeBridge, echo: Echo): List<JsonObject> {
        val request = prepare(manager, echo, peer = true)
        var posted = ""
        checkNotNull(request.roundInterceptor).intercept(request.requestBody.toString(), RecordingSink()) {
            posted = it
            RoundResult.Outcome(completedOutcome())
        }
        return items(posted)
    }

    private fun prepare(manager: CodexCodeModeBridge, echo: Echo? = null, peer: Boolean = false): BuiltTurn {
        val branch = echo?.branch ?: "branch A"
        val callback = callbackMessages(echo)
        val tail = if (peer) """,{"role":"user","content":"Continue."}""" else ""
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-6-astra","max_tokens":100,"tools":[{"name":"Read","input_schema":{"type":"object"}}],""" +
                """"messages":[{"role":"user","content":"start"},{"role":"user","content":"$branch"}$callback$tail]}""",
        )
        val builder = CodexCodeModeTurnBuilder(manager, media(), codeModeOnly = backendCodeModeOnly)
        val built = built("gpt-6-astra", lite = true)
        val input = built.requestBody.getValue("input").jsonArray.map { it.jsonObject }.toMutableList()
        input += item("""{"role":"user","content":"start"}""")
        input += item("""{"role":"user","content":"$branch"}""")
        echo?.let { input += item("""{"type":"function_call","call_id":"${it.id}","name":"Read","arguments":"{}"}""") }
        val renderedMedia = builder.toolMedia(body)
        builder.toolResults(body).forEach { result ->
            input += buildJsonObject {
                put("type", "function_call_output")
                put("call_id", result.id)
                put("output", result.output)
            }
            input += renderedMedia[result.id].orEmpty().map { it.jsonObject }
        }
        if (peer) input += item("""{"role":"user","content":"Continue."}""")
        val request = built.copy(
            requestBody = buildJsonObject { put("input", JsonArray(input)) },
            meta = built.meta.copy(
                scope = TurnScope(conversationKey = "splice-first-message", sessionId = echo?.session ?: "session-a"),
            ),
        )
        return builder.prepare(body, echo?.session ?: "session-a", request)
    }

    private fun callbackMessages(echo: Echo?): String = echo?.let {
        val result = """{"type":"tool_result","tool_use_id":"${it.id}","content":[${it.parts}]}"""
        val results = if (it.duplicate) "$result,$result" else result
        """,{"role":"assistant","content":[{"type":"tool_use","id":"${it.id}","name":"Read","input":{}}]},""" +
            """{"role":"user","content":[$results]}"""
    }.orEmpty()

    private fun reasoning(): String = checkNotNull(
        ReasoningReplay.encodeReasoningEnvelope(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_cleared")
                put("encrypted_content", "fixture-cleared")
            },
        ),
    )

    private fun items(body: String): List<JsonObject> = Json.parseToJsonElement(body).jsonObject.getValue("input")
        .jsonArray.map { it.jsonObject }

    private fun item(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
    private fun textPart(text: String): String = buildJsonObject {
        put("type", "text")
        put("text", text)
    }.toString()

    private fun imageParts(data: String): String = textPart("same caption") + "," + imagePart(data)
    private fun imagePart(data: String): String =
        """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$data"}}"""
}

private const val CLEARED_CALLBACK_MARKER = "[Old tool result content cleared]"
