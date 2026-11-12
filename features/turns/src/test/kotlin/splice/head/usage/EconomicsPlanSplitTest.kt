// Oct 11, 2026 (fin): a plan's requests and a key's are recorded apart, so the hour says who paid.
package splice.head.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
import splice.core.turn.UsageHistory
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

private const val SPLIT_HOUR = 3_600_000L
private const val CARDED = "carded"
private val CARD = TurnPrice(
    ModelCatalog(
        discoveryPrefix = "synthetic--",
        models = listOf(ModelEntry(CARDED, contextWindow = 100_000, rates = ModelRates(1.0, 0.1, 4.0))),
        defaultContextWindow = 100_000,
    ),
)

private fun carried(model: String = CARDED) = TurnEconomics(
    model = model,
    tokens = TurnTokens(inTokens = 1_000, cachedTokens = 0, cacheWriteTokens = 0, outTokens = 100),
    bytes = TurnBytes(reqBytes = null, upstreamBytes = null),
    tools = TurnTools(toolsEager = null, toolsDeferred = null),
    history = UsageHistory(),
)

class EconomicsPlanSplitTest {
    private val onPlan = AccountOnPlan { it == "plan" }

    @Test
    fun `a plan account and a key account in one hour are recorded apart`(@TempDir dir: Path) {
        val store = EconomicsStore(dir.resolve("e.json"), CARD, WallClock { 10 * SPLIT_HOUR }, plans = onPlan)
        store.record(carried(), "plan")
        store.record(carried("uncarded"), "plan")
        store.record(carried(), "key")
        store.record(carried("uncarded"), "key")

        val hour = store.read().single()
        val one = CARD.usd(CARDED, carried().counters(), 10 * SPLIT_HOUR)!!
        assertEquals(one, hour.costUsd!!, 1e-12, "the cost is the key request alone")
        assertEquals(1, hour.unpricedTurns, "the key request with no card stays unpriced")
        assertEquals(2, hour.cost.planTurns, "both plan requests, priced or not")
        assertEquals(one, hour.cost.planValueUsd!!, 1e-12, "what the plan request would have cost at the API rates")
        assertEquals(1, hour.cost.planUnpricedTurns)
    }

    @Test
    fun `the split survives a reload, and an hour written before it reads unsplit`(@TempDir dir: Path) {
        val file = dir.resolve("e.json")
        val store = EconomicsStore(file, CARD, WallClock { 10 * SPLIT_HOUR }, plans = onPlan)
        store.record(carried(), "plan")
        store.flushNow()
        val again = EconomicsStore(file, CARD, WallClock { 10 * SPLIT_HOUR }, plans = onPlan).read().single()
        assertEquals(1, again.cost.planTurns)

        val old = dir.resolve("old.json")
        Files.writeString(old, """[{"hour":36000000,"turns":3,"cost_usd":1.5,"unpriced_turns":1}]""" + "\n")
        val oldStore = EconomicsStore(old, CARD, WallClock { 10 * SPLIT_HOUR }, plans = onPlan)
        assertNull(oldStore.read().single().cost.planTurns, "never zero: its cost mixes plan and key requests")
        oldStore.record(carried(), "plan")
        assertNull(oldStore.read().single().cost.planTurns, "a daemon adding to that hour does not make it split")
    }
}
