package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.parse.AnthropicParse
import splice.core.perf.InputDigest
import splice.core.reasoning.ReasoningHistory
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.ReasoningDisplay
import splice.core.util.JsonScalars
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ModelIds
import splice.dialect.responses.request.ReasoningHandoff
import splice.dialect.responses.request.RequestedReasoning
import splice.dialect.responses.request.ResponsesRequestBuilder
import java.nio.file.Path

class ReasoningOptionalOrderBudgetTest(@param:TempDir private val state: Path) {
    private val key = "splice-" + "a".repeat(32)
    private val indexes = ReasoningPositionIndex()
    private val position = ReasoningHistory().apply { text("user", "Start") }.key
    private val request = AnthropicParse.parseAnthropicBody(
        """{"model":"gpt-5.6-sol","max_tokens":100,"messages":[{"role":"user","content":"Start"}]}""",
    )
    private val builder =
        ResponsesRequestBuilder(ResponsesQuirks(providerTag = "fixture", lite = ResponsesLiteQuirks()))

    @Test
    fun `optional order proof yields to reasoning without losing authoritative order across restart`() {
        val observed = listOf(reasoning("A"), reasoning("B"), reasoning("C"))
        val large = reasoning("D", "d".repeat(8192))
        val budget = (observed + large).sumOf { envelope(it).length.toLong() }
        val cache = cache(budget)
        retain(cache, observed, terminal = false, round = "first")
        val authoritative = observed.reversed()
        retain(cache, authoritative, terminal = true, round = "first")
        assertEquals(authoritative, native(cache))
        retain(cache, listOf(large), terminal = false, round = "second")
        val expected = authoritative + large
        assertEquals(expected, native(cache))
        assertEquals(expected, native(cache))
        assertEquals(expected, native(cache(budget)))
    }

    // REWRITTEN 2026-10-26 (fix 3): a cache writes its records behind its puts, on its write lane, so a restart first
    // lands every earlier lane's records (the drain the head's stop runs), and each lane is drained after the cell. The
    // restart here used to read the files right after the puts, which held only while each put forced its record to
    // disk.
    private val lanes = mutableListOf<ReasoningCacheWrites>()

    @AfterEach
    fun landAll() = land()

    private fun land() = lanes.forEach { it.drain(LAND_MS) }

    private fun cache(budget: Long): ReasoningCache {
        land()
        return ReasoningCache(
            maxTotalBytes = budget,
            writes = ReasoningCacheWrites(ReasoningCacheFiles(state) {}).also(lanes::add),
        )
    }

    private fun retain(cache: ReasoningCache, items: List<JsonObject>, terminal: Boolean, round: String) {
        val batch = if (terminal) InputDigest.hex(JsonArray(items)) else null
        items.forEachIndexed { index, item ->
            val id = checkNotNull(JsonScalars.str(item, "id"))
            val envelope = envelope(item)
            cache.put(
                key,
                listOf(indexes.index(position, envelope, ReasoningItemOrder(round, index, terminal, id, batch))),
                listOf(envelope),
            )
        }
    }

    private fun native(cache: ReasoningCache): List<JsonObject> = builder.build(
        request.typed,
        request.raw,
        BuildOptions(
            compact = false,
            models = ModelIds("gpt-5.6-sol", "gpt-5.6-sol"),
            reasoning = RequestedReasoning(null, null, ReasoningDisplay.OFF),
            handoff = ReasoningHandoff(
                replay = InjectPriorReasoning(true),
                decode = { ReasoningReplay.decodeReasoningEnvelope(it) },
                history = ReasoningHistoryLookup { indexes.positions(cache.snapshot(key)) },
            ),
            sessionId = "fixture-session",
        ),
    ).req.getValue("input").jsonArray.map { it.jsonObject }.filter { JsonScalars.str(it, "type") == "reasoning" }

    private fun reasoning(id: String, cipher: String = "fixture-$id"): JsonObject = buildJsonObject {
        put("type", "reasoning")
        put("id", "rs_$id")
        put("encrypted_content", cipher)
        put("summary", JsonArray(emptyList()))
    }

    private fun envelope(item: JsonObject): String = checkNotNull(ReasoningReplay.encodeReasoningEnvelope(item))
}

// How long a cell waits for a cache's records to land: far above what landing takes on a disk nobody holds.
private const val LAND_MS = 10_000L
