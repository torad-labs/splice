package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.parse.AnthropicParse
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.TurnOutcome
import splice.core.turn.TurnScope
import splice.core.util.JsonScalars
import splice.dialect.responses.buildResponsesTestRequest
import splice.upstream.BuiltTurn
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeStep

/** Uses the ordinary dialect renderer: its image output text differs from the script's annotated text. */
class CodeModeClearedImageChainTest : CodeModeBridgeTestSupport() {
    private data class Chain(
        val manager: CodexCodeModeBridge,
        val ids: List<String>,
        val canonical: List<JsonObject>,
    )

    /** Fix 2, goal item 3: a result Claude Code cleared, as its context management clears old tool results, is a part
     *  of the history Claude Code changed, so it goes out as Claude Code sent it: the client's tool call with the
     *  cleared text, never GPT's script with the content Claude Code cleared. Replaces the Oct 2026 rule that kept
     *  GPT's canonical script for a verified clear. */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cleared image callbacks go out as the client's tool calls with the cleared text`(restart: Boolean) = runTest {
        val chain = acceptedChain()
        val manager = if (restart) bridge(QueuedRuntime(ArrayDeque())) else chain.manager
        val history = history(chain, text(IMAGE_CHAIN_CLEAR), text(IMAGE_CHAIN_CLEAR))
        val next = message("Inspect next.")
        val request = prepare(manager, history + next)
        val sent = post(request, completedOutcome()).second
        chain.ids.forEach { id ->
            val result = sent.single {
                JsonScalars.str(it, "type") == "function_call_output" && JsonScalars.str(it, "call_id") == id
            }
            assertTrue(IMAGE_CHAIN_CLEAR in result.toString(), "$id goes out cleared: $result")
        }
        assertFalse(sent.any { "AA==" in it.toString() })
        val calls = chain.canonical.filter { JsonScalars.str(it, "type") == "custom_tool_call" }
        val gpts = calls.mapNotNull { JsonScalars.str(it, "call_id") }.toSet()
        assertFalse(sent.any { JsonScalars.str(it, "call_id") in gpts })
    }

    @ParameterizedTest
    @ValueSource(strings = ["image-edit", "nonsole", "branch"])
    fun `an edited image or branch cannot authorize a later cleared callback`(change: String) = runTest {
        val chain = acceptedChain()
        val first = when (change) {
            "image-edit" -> image("BB==")
            "nonsole" -> text(IMAGE_CHAIN_CLEAR) + image("BB==")
            else -> text(IMAGE_CHAIN_CLEAR)
        }
        val branch = if (change == "branch") "different branch" else "branch A"
        val messages = history(chain, first, text(IMAGE_CHAIN_CLEAR), branch)
        val sent = post(
            prepare(bridge(QueuedRuntime(ArrayDeque())), messages + message("Inspect next.")),
            completedOutcome(),
        ).second
        assertFalse(sent.any { JsonScalars.str(it, "call_id") == "outer-image-chain-2" })
        assertFalse(sent.any { JsonScalars.str(it, "id") == "rs_image_chain_2" })
        assertTrue(sent.any { JsonScalars.str(it, "call_id") == chain.ids.last() })
        if (change == "image-edit") {
            assertTrue(sent.any { "BB==" in it.toString() })
            assertFalse(sent.any { "AA==" in it.toString() })
        }
    }

    private suspend fun acceptedChain(): Chain {
        val steps = (1..2).map { n ->
            ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("read-$n", "Read"))), CodeModeStep.Completed("output $n")))
        }
        val manager = bridge(QueuedRuntime(ArrayDeque(steps)))
        val ids = mutableListOf<String>()
        var messages = listOf(message("start"), message("branch A"))
        var canonical = emptyList<JsonObject>()
        for (n in 1..2) {
            val source = outerOutcome("outer-image-chain-$n").run {
                copy(handoffs = handoffs.copy(reasoningEnvelopes = listOf(reasoning(n))))
            }
            val id = post(prepare(manager, messages), source).first.tools.single().id
            ids += id
            messages = messages + callback(id, if (n == 1) image("AA==") else text("second contents"))
            canonical = post(prepare(manager, messages), completedOutcome()).second
            if (n == 1) messages = messages + message("Second task.")
        }
        return Chain(manager, ids, canonical)
    }

    private fun history(
        chain: Chain,
        first: List<JsonObject>,
        second: List<JsonObject>,
        branch: String = "branch A",
    ): List<JsonObject> = listOf(message("start"), message(branch)) + callback(chain.ids.first(), first) +
        message("Second task.") + callback(chain.ids.last(), second)

    private fun prepare(manager: CodexCodeModeBridge, messages: List<JsonObject>): BuiltTurn {
        val raw = buildJsonObject {
            put("model", "gpt-6-astra")
            put("max_tokens", 100)
            put("tools", Json.parseToJsonElement("""[{"name":"Read","input_schema":{"type":"object"}}]"""))
            put("messages", JsonArray(messages))
        }
        val body = AnthropicParse.parseAnthropicBody(raw.toString())
        val wire = buildResponsesTestRequest(CodexQuirks().defaultQuirks(), body, "gpt-6-astra")
        val initial = built("gpt-6-astra", lite = true)
        return CodexCodeModeTurnBuilder(manager, media(), codeModeOnly = backendCodeModeOnly).prepare(
            body,
            "session-a",
            initial.copy(
                requestBody = wire,
                meta = initial.meta.copy(
                    scope = TurnScope(conversationKey = "splice-first-message", sessionId = "session-a"),
                ),
            ),
        )
    }

    private suspend fun post(request: BuiltTurn, outcome: TurnOutcome): Pair<RecordingSink, List<JsonObject>> {
        val sink = RecordingSink()
        var sent = emptyList<JsonObject>()
        checkNotNull(request.roundInterceptor).intercept(request.requestBody.toString(), sink) { posted ->
            sent = Json.parseToJsonElement(posted).jsonObject.getValue("input").jsonArray.map { it.jsonObject }
            RoundResult.Outcome(outcome)
        }
        return sink to sent
    }

    private fun callback(id: String, parts: List<JsonObject>): List<JsonObject> = listOf(
        buildJsonObject {
            put("role", "assistant")
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "tool_use")
                            put("id", id)
                            put("name", "Read")
                            put("input", JsonObject(emptyMap()))
                        },
                    ),
                ),
            )
        },
        buildJsonObject {
            put("role", "user")
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", id)
                            put("content", JsonArray(parts))
                        },
                    ),
                ),
            )
        },
    )

    private fun text(value: String): List<JsonObject> = listOf(
        buildJsonObject {
            put("type", "text")
            put("text", value)
        },
    )

    private fun image(data: String): List<JsonObject> = text("same caption") + Json.parseToJsonElement(
        """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$data"}}""",
    ).jsonObject

    private fun message(value: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", value)
    }

    private fun reasoning(n: Int): String = checkNotNull(
        ReasoningReplay.encodeReasoningEnvelope(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_image_chain_$n")
                put("encrypted_content", "fixture-image-chain-$n")
            },
        ),
    )
}

private const val IMAGE_CHAIN_CLEAR = "[Old tool result content cleared]"
