// Walls for the gateway-side tool_search answer (ResponsesToolSearch.kt): the continuation is an
// APPEND-ONLY extension of the prior request (closed-DTO continuationRequest, every non-input field
// untouched), the stop conditions (hasToolUse, empty toolSearches, round cap), the exhaustive-vs-
// ranked answer split, limit clamping, call_id dedup, and the no-dangling-reasoning-item rule.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.ToolSearchCall
import splice.core.turn.ToolSearchCallId
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.wire.ToolDefinition
import splice.dialect.responses.ResponsesRequest
import splice.dialect.responses.ResponsesToolSearchController
import splice.dialect.responses.ToolDeferralPolicy
import splice.dialect.responses.ToolSearchIndex
import splice.dialect.responses.responsesRequestJson
import splice.spi.ToolSearchRound
import splice.core.reasoning.ReasoningReplay.decodeReasoningEnvelope as coreDecode

private val DEFERRED = List(12) { ToolDefinition(name = "mcp__exa__tool_$it", description = "tool number $it") }
private val POLICY = ToolDeferralPolicy(searchLimit = 4, searchRounds = 3)
private val CONTROLLER = ResponsesToolSearchController(
    index = ToolSearchIndex(DEFERRED),
    policy = POLICY,
    emitStrict = false,
    forceStrictFalse = false,
    normalizeSchemas = false,
    decodeReasoningEnvelope = { coreDecode(it) },
)

private val priorRequest: JsonObject = Json.parseToJsonElement(
    """{"model":"gpt-5.6-sol","input":[{"role":"developer","content":"hi"}],
        "store":false,"stream":true,"prompt_cache_key":"splice-abc",
        "tool_choice":"auto","parallel_tool_calls":false}""",
).jsonObject

private fun rawCall(callId: String, query: String, limit: Int? = null): JsonObject = buildJsonObject {
    put("type", "tool_search_call")
    put("call_id", callId)
    put("execution", "client")
    put(
        "arguments",
        buildJsonObject {
            put("query", query)
            limit?.let { put("limit", it) }
        }.toString(),
    )
}

private fun call(callId: String, query: String, limit: Int? = null) = ToolSearchCall(
    callId = ToolSearchCallId(callId),
    query = query,
    limit = limit,
    raw = rawCall(callId, query, limit),
)

private fun success(
    toolSearches: List<ToolSearchCall>,
    hasToolUse: Boolean = false,
    reasoningEnvelopes: List<String> = emptyList(),
    bodyText: String = "",
    emittedText: Boolean = false,
) = TurnOutcome.Success(
    hasToolUse = hasToolUse,
    incomplete = false,
    usage = Usage(),
    bodyText = bodyText,
    emittedText = emittedText,
    toolSearches = toolSearches,
    reasoningEnvelopes = reasoningEnvelopes,
)

/** The items a continuation APPENDED past the prior request's own input — the assertion surface
 *  for "append-only" tests, without repeating the two-sided subList expression at every call site. */
private fun appendedTail(next: JsonObject): List<JsonElement> {
    val prevSize = priorRequest["input"]!!.jsonArray.size
    val nextInput = next["input"]!!.jsonArray
    return nextInput.subList(prevSize, nextInput.size)
}

private fun envelopeFor(id: String): String = ReasoningReplay.encodeReasoningEnvelope(
    buildJsonObject {
        put("type", "reasoning")
        put("id", id)
        put("encrypted_content", "ENC-$id")
    },
)!!

class ResponsesToolSearchTest {

    @Test
    fun `continuation appends reasoning, verbatim call, and the output item - no other field changes`() {
        val c = call("ts_1", "web search")
        val outcome = success(listOf(c), reasoningEnvelopes = listOf(envelopeFor("rs_1")))
        val round = ToolSearchRound(priorRequest, outcome, 0)
        val next = CONTROLLER.continuationForSearch(round)
        assertTrue(next != null)

        val prevInput = priorRequest["input"]!!.jsonArray
        val nextInput = next!!["input"]!!.jsonArray
        // append-only: the prior input is an untouched PREFIX
        assertEquals(prevInput.map { it.toString() }, nextInput.subList(0, prevInput.size).map { it.toString() })
        // reasoning, then the verbatim call, then the output item
        val tail = nextInput.subList(prevInput.size, nextInput.size).map { it.jsonObject }
        assertEquals("reasoning", tail[0]["type"]?.jsonPrimitive?.content)
        assertEquals("ENC-rs_1", tail[0]["encrypted_content"]?.jsonPrimitive?.content)
        assertEquals(c.raw.toString(), tail[1].toString())
        assertEquals("tool_search_output", tail[2]["type"]?.jsonPrimitive?.content)

        // every OTHER request field, decoded through the closed DTO, is byte-identical
        val prevDto = responsesRequestJson.decodeFromJsonElement(ResponsesRequest.serializer(), priorRequest)
        val nextDto = responsesRequestJson.decodeFromJsonElement(ResponsesRequest.serializer(), next)
        assertEquals(prevDto.copy(input = nextDto.input), nextDto)
    }

    @Test
    fun `the output item shape is exact - type, call_id, status, execution, deferred tools`() {
        val c = call("ts_1", "tool_0")
        val next = CONTROLLER.continuationForSearch(ToolSearchRound(priorRequest, success(listOf(c)), 0))!!
        val output = next["input"]!!.jsonArray.last().jsonObject
        assertEquals("tool_search_output", output["type"]?.jsonPrimitive?.content)
        assertEquals("ts_1", output["call_id"]?.jsonPrimitive?.content)
        assertEquals("completed", output["status"]?.jsonPrimitive?.content)
        assertEquals("client", output["execution"]?.jsonPrimitive?.content)
        val tools = output["tools"]!!.jsonArray
        assertTrue(tools.isNotEmpty())
        assertTrue(tools.all { it.jsonObject["defer_loading"]?.jsonPrimitive?.content == "true" })
    }

    @Test
    fun `hasToolUse true - no continuation`() {
        val c = call("ts_1", "x")
        val round = ToolSearchRound(priorRequest, success(listOf(c), hasToolUse = true), 0)
        assertNull(CONTROLLER.continuationForSearch(round))
    }

    @Test
    fun `empty toolSearches - no continuation`() {
        assertNull(CONTROLLER.continuationForSearch(ToolSearchRound(priorRequest, success(emptyList()), 0)))
    }

    @Test
    fun `final permitted round answers exhaustively - the entire deferred set`() {
        val c = call("ts_1", "tool_0") // a narrow query that would otherwise rank-limit
        val round = ToolSearchRound(priorRequest, success(listOf(c)), POLICY.searchRounds - 1)
        val next = CONTROLLER.continuationForSearch(round)!!
        val tools = next["input"]!!.jsonArray.last().jsonObject["tools"]!!.jsonArray
        assertEquals(DEFERRED.size, tools.size)
    }

    @Test
    fun `round index at or past the cap - no continuation`() {
        val c = call("ts_1", "x")
        val round = ToolSearchRound(priorRequest, success(listOf(c)), POLICY.searchRounds)
        assertNull(CONTROLLER.continuationForSearch(round))
    }

    @Test
    fun `blank query answers exhaustively rather than starving the model with an empty list`() {
        val c = call("ts_1", "   ")
        val next = CONTROLLER.continuationForSearch(ToolSearchRound(priorRequest, success(listOf(c)), 0))!!
        val tools = next["input"]!!.jsonArray.last().jsonObject["tools"]!!.jsonArray
        assertEquals(DEFERRED.size, tools.size)
    }

    @Test
    fun `the model's limit is clamped into 1 point policy searchLimit`() {
        val c = call("ts_1", "tool", limit = 999)
        val next = CONTROLLER.continuationForSearch(ToolSearchRound(priorRequest, success(listOf(c)), 0))!!
        val tools = next["input"]!!.jsonArray.last().jsonObject["tools"]!!.jsonArray
        assertEquals(POLICY.searchLimit, tools.size)
    }

    // review 2026-07-25 (comment 3): the sibling of the test just above, which only ever exercised
    // the UPPER bound. If clampedLimit lost its floor (a refactor to coerceAtMost, say), limit = 0
    // would answer with an empty tools[] — the exact starvation the blank-query branch exists to
    // avoid — and the suite would stay green without this.
    @Test
    fun `a limit of zero is clamped up to one tool`() {
        val c = call("ts_1", "tool", limit = 0)
        val next = CONTROLLER.continuationForSearch(ToolSearchRound(priorRequest, success(listOf(c)), 0))!!
        val tools = next["input"]!!.jsonArray.last().jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
    }

    @Test
    fun `a duplicate call_id in one round is answered exactly once`() {
        val calls = listOf(call("ts_1", "tool_0"), call("ts_1", "tool_1"))
        val next = CONTROLLER.continuationForSearch(ToolSearchRound(priorRequest, success(calls), 0))!!
        val tail = appendedTail(next)
        val outputs = tail.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "tool_search_output" }
        assertEquals(1, outputs.size)
        // review 2026-07-25 (comment 4): pin WHICH duplicate is answered, not just the count.
        // answeredOnce (ResponsesToolSearch.kt:136-140) is documented first-wins; "tool_0" (the
        // first call's query) matches only mcp__exa__tool_0 (no "tool_10"/"tool_11" substring
        // collision the way "tool_1" would) — a first->last regression would answer with
        // mcp__exa__tool_1 (and its "tool_1x" siblings) instead, and only this assertion catches it.
        val names = outputs.single()["tools"]!!.jsonArray.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertEquals(listOf("mcp__exa__tool_0"), names)
    }

    @Test
    fun `no reasoning envelopes - no dangling reasoning item injected`() {
        val c = call("ts_1", "tool_0")
        val next = CONTROLLER.continuationForSearch(ToolSearchRound(priorRequest, success(listOf(c)), 0))!!
        val tail = appendedTail(next)
        assertTrue(tail.none { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" })
        // exactly the verbatim call + its output, nothing else
        assertEquals(2, tail.size)
    }

    // review 2026-07-24 (HIGH/MEDIUM across reviews): the round's own prose is already on the
    // client's wire before the search call streamed — it must be replayed as context (the same
    // rule ResponsesReanchorController.assistantText applies), or the model re-says it and the
    // client sees it twice.
    @Test
    fun `the round's emitted prose is replayed as an assistant item before the call`() {
        val c = call("ts_1", "tool_0")
        val outcome = success(listOf(c), bodyText = "Let me find the right tool.", emittedText = true)
        val next = CONTROLLER.continuationForSearch(ToolSearchRound(priorRequest, outcome, 0))!!
        val tail = appendedTail(next)
        assertEquals("assistant", tail[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Let me find the right tool.", tail[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("tool_search_call", tail[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(3, tail.size, "assistant text + the verbatim call + its output, nothing else")
    }

    @Test
    fun `never-forwarded prose (emittedText false) is never replayed`() {
        val c = call("ts_1", "tool_0")
        // The FoldRunner-buffered shape: bodyText can be non-empty while emittedText is false (the
        // caller strips both before the outcome ever reaches the controller in production —
        // TurnDriver.bufferedForSearch) — pin the controller's OWN honesty regardless of the caller.
        val outcome = success(listOf(c), bodyText = "never sent", emittedText = false)
        val next = CONTROLLER.continuationForSearch(ToolSearchRound(priorRequest, outcome, 0))!!
        val tail = appendedTail(next)
        assertTrue(tail.none { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" })
        assertEquals(2, tail.size, "exactly the verbatim call + its output, nothing else")
    }
}
