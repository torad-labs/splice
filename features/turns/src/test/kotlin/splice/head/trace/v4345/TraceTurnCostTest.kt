// NEW: V4-345 — one turn's read carries what that turn cost, so the request a person opens says it beside
// its tokens and time. The daemon prices it (the console multiplies nothing, V4-221) at the head's card
// for the turn's own model, from the closing record's counters, the snapshot its perf row carries. A
// model with no card is a null, never $0; an open turn has no counters yet, so its read carries no cost;
// and the list, which carries no body, carries no cost either.
package splice.head.trace.v4345

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.perf.PerfSnapshot
import splice.core.storage.ActivityDays
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.trace.TraceQuery
import splice.head.trace.TraceRoute
import splice.head.wire.ClientInbound
import splice.head.wire.TurnIdMint
import java.nio.file.Path

private const val HEAD = "kimi"

// why: 2026-09-27T00:00Z, the day the file is named for
private const val DAY = 1_790_467_200_000L

// why: USD per million tokens on the priced model's card: fresh input, cache read, output
private val CARD = ModelRates(input = 1.0, cacheRead = 0.1, output = 4.0)

private val COUNTERS = mapOf("in_tokens" to 12_000L, "cached_tokens" to 9_000L, "out_tokens" to 340L)

// why: the card over COUNTERS by hand: 3,000 fresh input at $1, 9,000 cache reads at $0.10 and 340 output at
// $4, per million: (3,000 + 900 + 1,360) / 1,000,000
private const val PRICED_USD = 0.00526

// why: a dollar figure is a sum of products of doubles; this is far below a tenth of a cent
private const val USD_ULP = 1e-12

class TraceTurnCostTest {
    private val noCompaction = object : HeadCompactSource {
        override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
    }

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-kimi--",
        models = listOf(ModelEntry("kimi-k3", "Kimi K3", contextWindow = 256_000, rates = CARD)),
        defaultContextWindow = 256_000,
    )

    private val heads = TurnsHeadLookup { name ->
        if (name == HEAD) listOf(TurnsHead(HEAD, noCompaction, catalog)) else emptyList()
    }

    private fun meta(model: String) = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = "claude-kimi--$model",
        upstreamModel = model,
        clientMaxTokens = 8000,
        effort = "medium",
        summary = null,
        budgetTokens = null,
        sessionId = "sess-cost",
    )

    /** turn-1 ran on the priced model and turn-2 on one with no card, both ended; turn-3 is still open. */
    private fun writeTrace(dir: Path) {
        val ids = ArrayDeque(listOf("turn-1", "turn-2", "turn-3", "turn-4"))
        val days = ActivityDays(dir, HEAD, 7, WallClock { DAY }, true)
        val store = splice.head.syntheticTraceStore(
            days,
            HEAD,
            1 shl 20,
            now = WallClock { DAY },
            ids = TurnIdMint { ids.removeFirst() },
        )
        listOf("kimi-k3", "unlisted-model").forEach { model ->
            store.begin(meta(model), ClientInbound("POST", "/v1/messages", emptyMap(), "{}"))
                .finish("ok", PerfSnapshot(mapOf("total" to 900L), COUNTERS))
        }
        val _ = store.begin(meta("kimi-k3"), ClientInbound("POST", "/v1/messages", emptyMap(), "{}"))
        store.begin(meta("kimi-k3"), ClientInbound("POST", "/v1/messages", emptyMap(), "{}"))
            .finish("ok", PerfSnapshot(mapOf("total" to 900L), emptyMap()))
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
    }

    private fun read(dir: Path, turn: String?): JsonObject = runBlocking {
        val route = TraceRoute(heads, { dir }, Dispatchers.Unconfined, heap = splice.head.syntheticHeapBudget())
        val reply = route.read(HEAD, TraceQuery(null, null, turn))
        Json.parseToJsonElement(reply.body).jsonObject
    }

    @Test
    fun `an ended turn's read carries its cost at its own model's card`(@TempDir dir: Path) {
        writeTrace(dir)

        val cost = read(dir, "turn-1").getValue("cost_usd").jsonPrimitive.double

        assertEquals(PRICED_USD, cost, USD_ULP)
    }

    @Test
    fun `a model with no card reads as null, never zero`(@TempDir dir: Path) {
        writeTrace(dir)

        assertEquals(JsonNull, read(dir, "turn-2")["cost_usd"])
    }

    @Test
    fun `an ended turn without token counters does not claim a zero-dollar cost`(@TempDir dir: Path) {
        writeTrace(dir)

        assertEquals(JsonNull, read(dir, "turn-4")["cost_usd"])
    }

    @Test
    fun `an open turn and the list carry no cost`(@TempDir dir: Path) {
        writeTrace(dir)

        assertFalse(read(dir, "turn-3").containsKey("cost_usd"), "an open turn has no counters to price")
        val list = read(dir, null)
        assertFalse(list.containsKey("cost_usd"))
        assertTrue(list.getValue("turns").jsonArray.none { it.jsonObject.containsKey("cost_usd") })
    }
}
