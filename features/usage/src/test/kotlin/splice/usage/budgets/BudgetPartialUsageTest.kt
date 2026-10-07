package splice.usage.budgets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.nio.file.Path

private const val PARTIAL_BOOT = 20_000L * 86_400_000L + 3_600_000L

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetPartialUsageTest {
    private val model = "synthetic-priced"
    private val catalog = ModelCatalog(
        discoveryPrefix = "synthetic--",
        models = listOf(ModelEntry(model, contextWindow = 200_000, rates = ModelRates(1.0, 0.1, 4.0))),
        defaultContextWindow = 200_000,
    )
    private val inputOnly = mapOf(
        PerfKeys.IN_TOKENS to 1_000_000L,
        PerfKeys.CACHED_TOKENS to 0L,
        PerfKeys.CACHE_WRITE_TOKENS to 0L,
    )

    private fun owner(tmp: Path, history: List<PerfRow> = emptyList()): BudgetEnforcement {
        val store = BudgetStore(tmp.resolve("budget.json"), {})
        store.replace(listOf(Budget("synthetic", 0.5, BudgetActions.BLOCK)))
        val scope = TestScope()
        return BudgetEnforcement(
            store,
            BudgetAlert { _, _ -> },
            HeadPerfHistory { PerfRowsSource { PerfRowsWindow(history) } },
            {},
            WallClock { PARTIAL_BOOT },
            BudgetSeedRuntime(scope, UnconfinedTestDispatcher(scope.testScheduler), Ticker { false }),
        )
    }

    @Test
    fun `a torn stream still charges reported input while its complete spend stays unknown`(@TempDir tmp: Path) {
        val owner = owner(tmp)
        val head = owner.forHead("synthetic", catalog)
        head.spent(PARTIAL_BOOT, model, inputOnly)
        assertNotNull(head.admit(), "the reported input alone exceeds the spend limit")
        val spend = owner.spending("synthetic")!!
        assertFalse(spend.complete, "missing output makes the day a lower bound")
        assertNull(spend.usedUsd, "the lower bound must not be presented as complete spend")
    }

    @Test
    fun `historical torn streams enforce the same reported-input lower bound`(@TempDir tmp: Path) {
        val row = PerfRow(PARTIAL_BOOT - 1, "error:upstream-failed", inputOnly, model = model)
        val owner = owner(tmp, listOf(row))
        val head = owner.forHead("synthetic", catalog)
        assertNotNull(head.admit(), "seeded input cannot disappear because output was not reported")
        assertFalse(owner.spending("synthetic")!!.complete)
    }
}
