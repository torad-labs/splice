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
import splice.core.turn.TurnOutcome
import splice.core.turn.TurnScope
import splice.core.util.JsonScalars
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep

class CodeModeClearedCallbackChainTest : CodeModeBridgeTestSupport() {
    private data class Chain(
        val manager: CodexCodeModeBridge,
        val history: List<JsonObject>,
        val canonical: List<JsonObject>,
    )

    /** Fix 2, goal item 3: a result Claude Code cleared, as its context management clears old tool results, is a part
     *  of the history Claude Code changed, so it goes out as Claude Code sent it: the client's tool call with the
     *  cleared text, never GPT's script with the content Claude Code cleared. Replaces the Oct 2026 rule that kept
     *  GPT's canonical script for a verified clear. */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `several cleared accepted callbacks go out as the client's tool calls with the cleared text`(restart: Boolean) =
        runTest {
            val chain = acceptedChain()
            val manager = if (restart) bridge(QueuedRuntime(ArrayDeque())) else chain.manager
            val cleared = chain.history.map { item ->
                if (JsonScalars.str(item, "type") == "function_call_output") output(item, CHAIN_CLEAR_MARKER) else item
            }
            val next = message("Inspect next.")
            val sent = exchange(manager, cleared + next, completedOutcome()).second
            val results = cleared.filter { JsonScalars.str(it, "type") == "function_call_output" }
            assertTrue(results.isNotEmpty())
            results.forEach { result ->
                assertTrue(result in sent, "${JsonScalars.str(result, "call_id")} goes out cleared")
            }
            val calls = chain.canonical.filter { JsonScalars.str(it, "type") == "custom_tool_call" }
            val gpts = calls.mapNotNull { JsonScalars.str(it, "call_id") }.toSet()
            assertFalse(sent.any { JsonScalars.str(it, "call_id") in gpts })
            assertEquals(next, sent.last())
        }

    @ParameterizedTest
    @ValueSource(strings = ["changed", "duplicate"])
    fun `an unverifiable earlier callback cannot authorize a later cleared script`(kind: String) = runTest {
        val chain = acceptedChain()
        var number = 0
        val changed = chain.history.flatMap { item ->
            if (JsonScalars.str(item, "type") != "function_call_output") {
                listOf(item)
            } else if (number++ == 0) {
                if (kind == "duplicate") listOf(item, item) else listOf(output(item, "different first contents"))
            } else {
                listOf(output(item, CHAIN_CLEAR_MARKER))
            }
        }
        val sent = exchange(
            bridge(QueuedRuntime(ArrayDeque())),
            changed + message("Inspect next."),
            completedOutcome(),
        ).second
        assertFalse(sent.any { JsonScalars.str(it, "call_id") == "outer-chain-2" })
        assertFalse(sent.any { JsonScalars.str(it, "id") == "rs_chain_2" })
        assertEquals(
            changed.filter { JsonScalars.str(it, "type") == "function_call_output" },
            sent.filter { JsonScalars.str(it, "type") == "function_call_output" },
        )
    }

    private suspend fun acceptedChain(): Chain {
        val steps = (1..2).map { n ->
            ArrayDeque(
                listOf(CodeModeStep.Calls(listOf(call("read-$n", "Read"))), CodeModeStep.Completed("script $n output")),
            )
        }
        val manager = bridge(QueuedRuntime(ArrayDeque(steps)))
        var history = listOf(message("start"))
        var canonical = emptyList<JsonObject>()
        for (n in 1..2) {
            val source = outerOutcome("outer-chain-$n").run {
                copy(handoffs = handoffs.copy(reasoningEnvelopes = listOf(reasoning(n))))
            }
            val issued = exchange(manager, history, source).first.tools.single().id
            history = history + callback(issued, "contents $n")
            canonical = exchange(manager, history, completedOutcome()).second
            if (n == 1) history = history + message("Second task.")
        }
        return Chain(manager, history, canonical)
    }

    private suspend fun exchange(
        manager: CodexCodeModeBridge,
        history: List<JsonObject>,
        outcome: TurnOutcome,
    ): Pair<RecordingSink, List<JsonObject>> {
        val raw = buildJsonObject {
            put("model", "gpt-6-astra")
            put("max_tokens", 100)
            put("tools", Json.parseToJsonElement("""[{"name":"Read","input_schema":{"type":"object"}}]"""))
            put("messages", JsonArray(history.map(::clientMessage)))
        }
        val body = AnthropicParse.parseAnthropicBody(raw.toString())
        val built = built("gpt-6-astra", lite = true)
        val input = built.requestBody.getValue("input").jsonArray + history
        val request = CodexCodeModeTurnBuilder(manager, media(), codeModeOnly = backendCodeModeOnly).prepare(
            body,
            "session-a",
            built.copy(
                requestBody = buildJsonObject { put("input", JsonArray(input)) },
                meta = built.meta.copy(
                    scope = TurnScope(conversationKey = "splice-first-message", sessionId = "session-a"),
                ),
            ),
        )
        val sink = RecordingSink()
        var sent = emptyList<JsonObject>()
        checkNotNull(request.roundInterceptor).intercept(request.requestBody.toString(), sink) { posted ->
            sent = Json.parseToJsonElement(posted).jsonObject.getValue("input").jsonArray.map { it.jsonObject }
            RoundResult.Outcome(outcome)
        }
        return sink to sent
    }

    private fun clientMessage(item: JsonObject): JsonObject = when (JsonScalars.str(item, "type")) {
        "function_call" -> buildJsonObject {
            put("role", "assistant")
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "tool_use")
                            put("id", item.getValue("call_id"))
                            put("name", item.getValue("name"))
                            put("input", Json.parseToJsonElement(checkNotNull(JsonScalars.str(item, "arguments"))))
                        },
                    ),
                ),
            )
        }
        "function_call_output" -> buildJsonObject {
            put("role", "user")
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", item.getValue("call_id"))
                            put("content", item.getValue("output"))
                        },
                    ),
                ),
            )
        }
        else -> item
    }

    private fun callback(id: String, contents: String): List<JsonObject> = listOf(
        Json.parseToJsonElement(
            """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}""",
        ).jsonObject,
        buildJsonObject {
            put("type", "function_call_output")
            put("call_id", id)
            put("output", contents)
        },
    )

    private fun output(item: JsonObject, text: String): JsonObject = JsonObject(
        item + buildJsonObject {
            put("output", text)
        },
    )
    private fun message(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", text)
    }

    private fun reasoning(n: Int): String = checkNotNull(
        ReasoningReplay.encodeReasoningEnvelope(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_chain_$n")
                put("encrypted_content", "fixture-chain-$n")
            },
        ),
    )
}

private const val CHAIN_CLEAR_MARKER = "[Old tool result content cleared]"
