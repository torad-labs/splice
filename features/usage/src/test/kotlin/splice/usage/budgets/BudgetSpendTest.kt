// NEW: the console reads the same daily ledger enforcement spends, without inventing missing prices.
package splice.usage.budgets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
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

private const val SPEND_DAY_MS = 86_400_000L
private const val SPEND_BOOT_MS = 20_000L * SPEND_DAY_MS + 3_600_000L

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetSpendTest {
    @TempDir
    lateinit var directory: Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "test--",
        models = listOf(ModelEntry("priced", contextWindow = 200_000, rates = ModelRates(1.0, 0.0, 0.0))),
        defaultContextWindow = 200_000,
    )

    private fun immediateSeed(): BudgetSeedRuntime {
        val scope = TestScope()
        return BudgetSeedRuntime(scope, UnconfinedTestDispatcher(scope.testScheduler), Ticker { false })
    }

    private fun tokens(usd: Long): Map<String, Long> = mapOf(PerfKeys.IN_TOKENS to usd * 1_000_000L)

    @Test
    fun `used and remaining read exactly the admission tally across repeated head wiring and UTC midnight`() {
        val store = BudgetStore(directory.resolve("budgets.json"))
        store.replace(listOf(Budget("native", 5.0, BudgetActions.BLOCK)))
        var now = SPEND_BOOT_MS
        val before = PerfRow(SPEND_BOOT_MS - 1, "ok", tokens(1), model = "priced")
        val history = HeadPerfHistory { PerfRowsSource { PerfRowsWindow(listOf(before)) } }
        val owner = BudgetEnforcement(store, BudgetAlert { _, _ -> }, history, {}, WallClock { now }, immediateSeed())
        val head = owner.forHead("native", catalog)
        head.spent(now, "priced", tokens(2))
        assertSame(head, owner.forHead("native", catalog))
        assertEquals(BudgetSpend(3.0, 2.0, 0, true), owner.spending("native"))
        assertNull(head.admit())
        head.spent(now, "priced", tokens(3))
        assertEquals(BudgetSpend(6.0, 0.0, 0, true), owner.spending("native"))
        assertTrue(head.admit() != null)
        now += SPEND_DAY_MS
        assertEquals(BudgetSpend(0.0, 5.0, 0, true), owner.spending("native"))
        assertNull(head.admit())
    }

    @Test
    fun `unpriced turns and unreadable history cannot produce exact money amounts`() {
        val store = BudgetStore(directory.resolve("budgets.json"))
        store.replace(listOf(Budget("head", 5.0, BudgetActions.WARN)))
        val unreadable = PerfRowsWindow(emptyList(), readError = "fixture unreadable")
        val broken = HeadPerfHistory { PerfRowsSource { unreadable } }
        val owner = BudgetEnforcement(
            store,
            BudgetAlert { _, _ -> },
            broken,
            {},
            WallClock { SPEND_BOOT_MS },
            immediateSeed(),
        )
        val head = owner.forHead("head", catalog)
        head.spent(SPEND_BOOT_MS, "unpriced", emptyMap())
        val spend = owner.spending("head")!!
        assertNull(spend.usedUsd)
        assertNull(spend.remainingUsd)
        assertEquals(1, spend.unpricedTurns)
        assertFalse(spend.complete)
        assertNull(owner.spending("unwired"))
    }

    @Test
    fun `budget GET includes head-wide amounts and never any account allocation`() {
        val paths = StatePaths(baseOverride = directory.resolve("state"))
        val store = BudgetStore(paths.stateDir.resolve("budgets.json"))
        store.replace(listOf(Budget("native", 5.0, BudgetActions.WARN)))
        val history = HeadPerfHistory { PerfRowsSource { PerfRowsWindow(emptyList()) } }
        val owner = BudgetEnforcement(
            store,
            BudgetAlert { _, _ -> },
            history,
            {},
            WallClock { SPEND_BOOT_MS },
            immediateSeed(),
        )
        owner.forHead("native", catalog).spent(SPEND_BOOT_MS, "priced", tokens(2))
        val route = BudgetRoutes(BudgetSource { store }, ConfigService(paths))
        val reply = route.read(owner)
        assertTrue(reply.body.contains("\"used_usd\":2.0"))
        assertTrue(reply.body.contains("\"remaining_usd\":3.0"))
        assertFalse(reply.body.contains("account"))
        assertTrue(route.read().body.contains("\"used_usd\":null"))
    }
}
