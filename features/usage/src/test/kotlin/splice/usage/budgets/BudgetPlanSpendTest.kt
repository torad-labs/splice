// Oct 11, 2026 (Marlin): a mixed command's budget caps what a KEY was charged. A request on a plan account is
// neither spent nor unpriced, today's or from before this daemon started.
package splice.usage.budgets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.perf.PerfKeys
import splice.core.util.WallClock
import splice.upstream.Ticker
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.perf.PerfTurnFacts
import java.nio.file.Path
import java.time.ZoneOffset

private const val PLAN_BOOT = 20_000L * 86_400_000L + 3_600_000L

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetPlanSpendTest {
    private val model = "synthetic-priced"
    private val catalog = ModelCatalog(
        discoveryPrefix = "synthetic--",
        models = listOf(ModelEntry(model, contextWindow = 200_000, rates = ModelRates(1.0, 0.1, 4.0))),
        defaultContextWindow = 200_000,
    )

    // One million input tokens at $1 a million: exactly one dollar.
    private val oneDollar = mapOf(
        PerfKeys.IN_TOKENS to 1_000_000L,
        PerfKeys.CACHED_TOKENS to 0L,
        PerfKeys.CACHE_WRITE_TOKENS to 0L,
        PerfKeys.OUT_TOKENS to 0L,
    )
    private val onPlan = BudgetPlans { it == "plan" }

    private fun owner(tmp: Path, history: List<PerfRow> = emptyList()): BudgetEnforcement {
        val store = BudgetStore(tmp.resolve("budget.json"), {})
        store.replace(listOf(Budget("synthetic", 3.0, BudgetActions.BLOCK)))
        val scope = TestScope()
        return BudgetEnforcement(
            store,
            BudgetAlert { _, _ -> },
            HeadPerfHistory { PerfRowsSource { PerfRowsWindow(history) } },
            {},
            WallClock { PLAN_BOOT },
            BudgetSeedRuntime(scope, UnconfinedTestDispatcher(scope.testScheduler), Ticker { false }),
            ZoneOffset.UTC,
        )
    }

    @Test
    fun `plan requests are not spent and a key request is, so a plan never trips the block`(@TempDir tmp: Path) {
        val owner = owner(tmp)
        val head = owner.forHead("synthetic", catalog, onPlan)
        repeat(5) { head.spentOn("plan", PLAN_BOOT, model, oneDollar) }
        assertNull(head.admit(), "five dollars of plan requests are no key spend")
        head.spentOn("key", PLAN_BOOT, model, oneDollar)
        assertNull(head.admit(), "one key dollar of three")
        head.spentOn("plan", PLAN_BOOT, "synthetic-no-card", oneDollar)
        assertEquals(0L, owner.spending("synthetic")!!.unpricedTurns, "a plan request is no request without a price")
        head.spentOn("key", PLAN_BOOT, model, oneDollar + (PerfKeys.IN_TOKENS to 2_000_000L))
        assertNotNull(head.admit(), "three more key dollars reach the limit")
    }

    @Test
    fun `plan requests from before the daemon started are left out of today's spend`(@TempDir tmp: Path) {
        fun row(ts: Long, account: String) =
            PerfRow(ts, "ok", oneDollar, facts = PerfTurnFacts(model = model, account = account))
        val history = listOf(row(PLAN_BOOT - 3, "plan"), row(PLAN_BOOT - 2, "plan"), row(PLAN_BOOT - 1, "key"))
        val owner = owner(tmp, history)
        val head = owner.forHead("synthetic", catalog, onPlan)
        assertNull(head.admit(), "two plan dollars and one key dollar are one dollar of 3")
        assertEquals(1.0, owner.spending("synthetic")!!.usedUsd!!, 1e-9)
    }
}
