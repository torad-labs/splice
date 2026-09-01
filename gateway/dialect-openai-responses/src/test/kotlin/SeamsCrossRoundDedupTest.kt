// NEW: cross-ROUND restatement suppression through the REAL ResponsesTurnSeams (registry
// included), constructed the way the daemon constructs it (codex-shaped quirks), with two rounds
// sharing one TurnMeta exactly like FoldRunner/ReanchorRunner rounds do (2026-08-26, reworked
// for the sequential_cutoff codex-parity port: rounds deliver summary parts as done-events under
// fresh item ids; the conversation registry is what suppresses the cross-round restatement).
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.LogSink
import splice.dialect.responses.ConversationSummaryParts
import splice.dialect.responses.ReasoningCache
import splice.dialect.responses.ReasoningCachePolicy
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesStableIds
import splice.dialect.responses.ResponsesTurnOptions
import splice.dialect.responses.ResponsesTurnSeams
import splice.dialect.responses.ResponsesTurnSeamsDeps
import splice.dialect.responses.ToolSurfaceLatch
import splice.dialect.responses.TurnOptionsDeps
import splice.spi.TurnSignals
import splice.spi.WireSink

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

private fun codexSeams(): ResponsesTurnSeams {
    val quirks = ResponsesQuirks(
        providerTag = "claudex",
        summaryDelivery = "sequential_cutoff",
        forceStrictFalse = true,
    )
    val cachePolicy = ReasoningCachePolicy()
    val ids = ResponsesStableIds()
    val reasoningCache = ReasoningCache()
    return ResponsesTurnSeams(
        ResponsesTurnSeamsDeps(
            quirks = quirks,
            cachePolicy = cachePolicy,
            ids = ids,
            reasoningCache = reasoningCache,
            summaryParts = ConversationSummaryParts(),
            turnOptions = ResponsesTurnOptions(
                TurnOptionsDeps(
                    showReasoning = ReasoningDisplay.TEXT,
                    replayReasoning = false,
                    configEffort = "high",
                    configSummary = "detailed",
                    quirks = quirks,
                    cachePolicy = cachePolicy,
                    ids = ids,
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry(id = "gpt-5.6-luna", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    log = LogSink {},
                    reasoningCache = reasoningCache,
                    toolSurfaceLatch = ToolSurfaceLatch(),
                ),
            ),
            foldConfig = null,
            replayReasoning = false,
            streamIdleMs = 180_000,
            upstreamTimeoutMs = 900_000,
        ),
    )
}

private fun testMeta(): TurnMeta = TurnMeta(
    compact = false,
    showReasoning = ReasoningDisplay.TEXT,
    stream = true,
    originalModel = "claude-codex--gpt-5.6-luna",
    upstreamModel = "gpt-5.6-luna",
    clientMaxTokens = 8000,
    effort = "high",
    summary = "detailed",
    budgetTokens = 31999,
    conversationKey = "splice-testconvokey",
)

class SeamsCrossRoundDedupTest {

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
