// NEW: cross-ROUND restatement suppression through the REAL ResponsesTurnSeams (registry
// included), constructed the way the daemon constructs it (codex-shaped quirks), with two rounds
// sharing one TurnMeta exactly like FoldRunner/ReanchorRunner rounds do (2026-08-26, reworked
// for the sequential_cutoff codex-parity port: rounds deliver summary parts as done-events under
// fresh item ids; the conversation registry is what suppresses the cross-round restatement).
package splice.dialect.responses.stream

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.parse.AnthropicTurnBody
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import splice.core.util.LogSink
import splice.dialect.responses.ReasoningContinuity
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesReasoningQuirks
import splice.dialect.responses.ResponsesRoundTripQuirks
import splice.dialect.responses.ResponsesToolQuirks
import splice.dialect.responses.ResponsesTurnOptions
import splice.dialect.responses.ResponsesTurnSeams
import splice.dialect.responses.ResponsesTurnSeamsDeps
import splice.dialect.responses.RoundCarry
import splice.dialect.responses.TranslatorServices
import splice.dialect.responses.WatchdogCaps
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.ReasoningCache
import splice.dialect.responses.reasoning.ReasoningCachePolicy
import splice.dialect.responses.reasoning.ReasoningEnvelopeDecoder
import splice.dialect.responses.reasoning.ReasoningHistoryLookup
import splice.dialect.responses.reasoning.ReasoningPositionIndex
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ModelIds
import splice.dialect.responses.request.ReasoningHandoff
import splice.dialect.responses.request.RequestedReasoning
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.dialect.responses.request.ResponsesStableIds
import splice.dialect.responses.tools.ToolSurfaceLatch
import splice.upstream.TurnSignals
import splice.upstream.sse.WireSink

private class SeamsSink : WireSink {
    val out = mutableListOf<String>()
    private var next = 0
    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        out.add(thinking)
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) {
        out.add("redacted:$data")
    }
}

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private val completed = ev(
    """{"type":"response.completed","response":{"id":"r1","usage":{"input_tokens":1,"output_tokens":1}}}""",
)

private fun round(id: String, vararg parts: String): List<JsonObject> = buildList {
    add(ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"$id"}}"""))
    parts.forEachIndexed { i, p ->
        add(
            ev(
                """{"type":"response.reasoning_summary_text.done","item_id":"$id","output_index":0,""" +
                    """"summary_index":$i,"text":"$p"}""",
            ),
        )
    }
    add(completed)
}

private fun codexSeams(
    log: LogSink = LogSink {},
    reasoningCache: ReasoningCache = ReasoningCache(),
    cacheOn: Boolean = false,
    turnOptions: ResponsesTurnOptions? = null,
): ResponsesTurnSeams {
    val quirks = ResponsesQuirks(
        lite = ResponsesLiteQuirks(),
        providerTag = "claudex",
        reasoning = ResponsesReasoningQuirks(
            summaryDelivery = "sequential_cutoff",
        ),
        roundTrip = ResponsesRoundTripQuirks(reasoningCache = cacheOn),
        tools = ResponsesToolQuirks(
            forceStrictFalse = true,
        ),
    )
    val cachePolicy = ReasoningCachePolicy()
    val ids = ResponsesStableIds()
    val continuity = ReasoningContinuity(reasoningCache, cachePolicy, ids)
    return ResponsesTurnSeams(
        ResponsesTurnSeamsDeps(
            quirks = quirks,
            continuity = continuity,
            summaryParts = ConversationSummaryParts(),
            turnOptions = turnOptions ?: ResponsesTurnOptions(
                ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
                quirks,
                ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry(id = "gpt-5.6-luna", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                LogSink {},
                continuity,
                ToolSurfaceLatch(),
            ),
            carry = RoundCarry(foldConfig = null),
            caps = WatchdogCaps(streamIdleMs = 180_000, upstreamTimeoutMs = 900_000),
            services = TranslatorServices(log = log),
        ),
    )
}

/** The provider's real options, translator seams and request builder with a deliberately small cache. */
private class ReasoningCapacityReplay(cache: ReasoningCache) {
    private val quirks = ResponsesQuirks(
        providerTag = "claudex",
        lite = ResponsesLiteQuirks(),
        roundTrip = ResponsesRoundTripQuirks(reasoningCache = true),
    )
    private val options = ResponsesTurnOptions(
        ReasoningSettings(ReasoningDisplay.TEXT, true, null, null),
        quirks,
        ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry(id = "gpt-5.6-luna", contextWindow = 272_000)),
            defaultContextWindow = 272_000,
        ),
        LogSink {},
        ReasoningContinuity(cache, ReasoningCachePolicy(), ResponsesStableIds()),
        ToolSurfaceLatch(),
    )
    private val seams = codexSeams(reasoningCache = cache, cacheOn = true, turnOptions = options)
    private val builder = ResponsesRequestBuilder(quirks)
    private val signals = TurnSignals(watchdogFired = { null }, clientGone = { false })

    suspend fun capture() {
        val call = ev("""{"type":"function_call","call_id":"call_1","name":"read","arguments":"{}"}""")
        drive(
            request(null),
            listOf(
                reasoning("r1"),
                ev("""{"type":"response.output_item.added","output_index":1,"item":$call}"""),
                ev("""{"type":"response.output_item.done","output_index":1,"item":$call}"""),
                completed,
            ),
        )
        drive(request("original"), listOf(reasoning("r2"), completed))
    }

    fun ids(result: String): List<String> {
        val body = request(result)
        val input = builder.build(body.typed, body.raw, options.build(body, false, "session")).req
            .getValue("input").jsonArray.map { it.jsonObject }
        return input.filter { it["type"]?.jsonPrimitive?.content == "reasoning" }
            .map { it.getValue("id").jsonPrimitive.content }
    }

    private suspend fun drive(body: AnthropicTurnBody, frames: List<JsonObject>) {
        val built = builder.build(body.typed, body.raw, options.build(body, false, "session"))
        seams.streamTranslator(built.meta, signals).driveTurn(frames.asFlow(), SeamsSink())
    }

    private fun reasoning(id: String): JsonObject = ev(
        """{"type":"response.output_item.done","output_index":0,""" +
            """"item":{"type":"reasoning","id":"$id","encrypted_content":"x"}}""",
    )

    private fun request(result: String?): AnthropicTurnBody {
        val followup = result?.let {
            """,{"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"read","input":{}}]},""" +
                """{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"$it"}]}"""
        }.orEmpty()
        return AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-5.6-luna","messages":[{"role":"user","content":"Opening"}$followup],""" +
                """"tools":[{"name":"read","input_schema":{"type":"object"}}]}""",
        )
    }
}

private fun testMeta(): TurnMeta = TurnMeta(
    compact = false,
    reasoning = TurnReasoning(
        showReasoning = ReasoningDisplay.TEXT,
        effort = "high",
        summary = "detailed",
        budgetTokens = 31999,
    ),
    route = TurnRoute(
        stream = true,
        originalModel = "claude-codex--gpt-5.6-luna",
        upstreamModel = "gpt-5.6-luna",
        clientMaxTokens = 8000,
    ),
    scope = TurnScope(conversationKey = "splice-testconvokey"),
)

class SeamsCrossRoundDedupTest {

    @Test
    fun `result proof metadata leaves two reasoning entries available under a two-entry cap`() = runTest {
        val replay = ReasoningCapacityReplay(ReasoningCache(maxEntries = 2))
        replay.capture()

        assertEquals(listOf("r1", "r2"), replay.ids("original"))
        assertEquals(listOf("r1", "r2"), replay.ids("[Old tool result content cleared]"))
    }

    @Test
    fun `a rejected result proof preserves ordinary reasoning and misses cleared history`() = runTest {
        val bytes = listOf("r1", "r2").sumOf { id ->
            val item = ev("""{"type":"reasoning","id":"$id","encrypted_content":"x"}""")
            checkNotNull(ReasoningReplay.encodeReasoningEnvelope(item)).length.toLong()
        }
        val replay = ReasoningCapacityReplay(ReasoningCache(maxTotalBytes = bytes))
        replay.capture()

        assertEquals(listOf("r1", "r2"), replay.ids("original"))
        assertEquals(listOf("r1"), replay.ids("[Old tool result content cleared]"))
    }

    @Test
    fun `live and terminal copies leave entry and byte capacity for the next reasoning item`() = runTest {
        val first = ev("""{"type":"reasoning","id":"rs_one","encrypted_content":"cipher-one","summary":[]}""")
        val second = ev("""{"type":"reasoning","id":"rs_two","encrypted_content":"cipher-two","summary":[]}""")
        val bytes = listOf(first, second).sumOf {
            checkNotNull(ReasoningReplay.encodeReasoningEnvelope(it)).length.toLong()
        }
        for (cache in listOf(ReasoningCache(maxEntries = 2), ReasoningCache(maxTotalBytes = bytes))) {
            val body = AnthropicParse.parseAnthropicBody(
                """{"model":"gpt-5.6-luna","messages":[{"role":"user","content":"Opening"}]}""",
            )
            val options = BuildOptions(
                compact = false,
                models = ModelIds("gpt-5.6-luna", "gpt-5.6-luna"),
                reasoning = RequestedReasoning(null, null, ReasoningDisplay.TEXT),
                sessionId = "session",
                handoff = ReasoningHandoff(
                    replay = InjectPriorReasoning(true),
                    decode = ReasoningEnvelopeDecoder { ReasoningReplay.decodeReasoningEnvelope(it) },
                    history = ReasoningHistoryLookup {
                        val key = ReasoningCachePolicy().conversationKey(
                            "session",
                            ResponsesStableIds().reasoningStorageKey(body.typed),
                        )
                        ReasoningPositionIndex().positions(cache.snapshot(key))
                    },
                ),
            )
            val builder =
                ResponsesRequestBuilder(ResponsesQuirks(providerTag = "claudex", lite = ResponsesLiteQuirks()))
            val meta = builder.build(body.typed, body.raw, options).meta
            val seams = codexSeams(reasoningCache = cache, cacheOn = true)
            val signals = TurnSignals(clientGone = { false }, watchdogFired = { null })
            val firstRound = listOf(
                ev("""{"type":"response.output_item.done","output_index":0,"item":$first}"""),
                ev("""{"type":"response.completed","response":{"status":"completed","output":[$first]}}"""),
            )
            seams.streamTranslator(meta, signals).driveTurn(firstRound.asFlow(), SeamsSink())
            val nextRound = listOf(
                ev("""{"type":"response.output_item.done","output_index":0,"item":$second}"""),
                ev("""{"type":"response.completed","response":{"status":"completed","output":[]}}"""),
            )
            seams.streamTranslator(meta, signals).driveTurn(nextRound.asFlow(), SeamsSink())

            val replay = builder.build(body.typed, body.raw, options.copy()).req.getValue("input").jsonArray
                .map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "reasoning" }
            assertEquals(listOf("rs_one", "rs_two"), replay.map { it.getValue("id").jsonPrimitive.content })
        }
    }

    @Test
    fun `round 2's restatement of round 1 is suppressed through the real seams`() = runTest {
        val seams = codexSeams()
        val meta = testMeta()
        val signals = TurnSignals(clientGone = { false }, watchdogFired = { null })
        val p0 = "Planning zero downtime partitioning strategy - padded"
        val p1 = "Designing dual write migration approach - padded"
        val p2 = "Assessing partitioning constraints and FK strategies"

        val sink1 = SeamsSink()
        seams.streamTranslator(meta, signals).driveTurn(round("rs_r1", p0, p1).asFlow(), sink1)
        val sink2 = SeamsSink()
        seams.streamTranslator(meta, signals).driveTurn(round("rs_r2", p0, p1, p2).asFlow(), sink2)

        assertEquals(
            0,
            sink2.out.count { it.contains(p0) } + sink2.out.count { it.contains(p1) },
            "round-2 restatement leaked: ${sink2.out}",
        )
        assertEquals(1, sink2.out.count { it.contains(p2) }, "round-2 new part lost: ${sink2.out}")
    }
}

/** Oct 4: the ending line reaches daemon.log through the seams the daemon builds, tagged with the provider. */
class SeamsEndingLineTest {
    @Test
    fun `a failed round's ending line carries the provider tag through the real seams`() = runTest {
        val lines = mutableListOf<String>()
        val signals = TurnSignals(clientGone = { false }, watchdogFired = { null })
        codexSeams(LogSink(lines::add)).streamTranslator(testMeta(), signals).driveTurn(
            listOf(ev("""{"type":"error","status":429,"error":{"code":"rate_limit_exceeded"}}""")).asFlow(),
            SeamsSink(),
        )
        assertEquals(
            listOf("[claudex] upstream round ended on error status=429 code=rate_limit_exceeded type=-"),
            lines,
        )
    }
}
