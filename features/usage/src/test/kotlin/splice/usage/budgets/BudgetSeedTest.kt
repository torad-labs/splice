// NEW: non-waiting admission, partial spend, boot policy and bounded background refresh contracts.
package splice.usage.budgets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

private const val SEED_DAY_MS = 86_400_000L
private const val SEED_BOOT_MS = 20_000L * SEED_DAY_MS + 3_600_000L
private const val POLICY_BOUND_MS = 1_000L
private val seedCatalog = ModelCatalog(
    discoveryPrefix = "synthetic--",
    models = listOf(ModelEntry("priced", contextWindow = 1_000, rates = ModelRates(1.0, 0.0, 0.0))),
    defaultContextWindow = 1_000,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetSeedTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `pending history is one flight and admission keeps concurrent live spend`() = runTest {
        val store = BudgetStore(directory.resolve("budgets.json"), {})
        store.replace(listOf(Budget("head", 5.0, BudgetActions.BLOCK)))
        var reads = 0
        val history = HeadPerfHistory {
            PerfRowsSource {
                reads++
                PerfRowsWindow(listOf(PerfRow(SEED_BOOT_MS - 1, "ok", tokens(1), model = "priced")))
            }
        }
        val owner = BudgetEnforcement(
            store,
            BudgetAlert { _, _ -> },
            history,
            {},
            WallClock { SEED_BOOT_MS },
            BudgetSeedRuntime(backgroundScope, StandardTestDispatcher(testScheduler)),
        )
        val head = owner.forHead("head", seedCatalog)
        head.spent(SEED_BOOT_MS, "priced", tokens(2))
        repeat(3) { assertNull(head.admit()) }
        assertEquals(0, reads, "admission has not executed the historical reader")
        assertEquals(BudgetSpend(null, null, 0, false, pending = true), owner.spending("head"))
        head.spent(SEED_BOOT_MS + 1, "priced", tokens(1))
        runCurrent()
        assertEquals(1, reads, "every pending admission shares one seed")
        assertEquals(BudgetSpend(4.0, 1.0, 0, true), owner.spending("head"))
        head.spent(SEED_BOOT_MS + 2, "priced", tokens(1))
        assertNotNull(head.admit(), "the seeded and live spend together enforce the cap")
        assertEquals(1, reads)
    }

    @Test
    fun `a saved budget on an empty day says history is pending then reports its measured zero`() = runTest {
        val paths = StatePaths(baseOverride = directory.resolve("empty-state"))
        val store = BudgetStore(paths.stateDir.resolve("budgets.json"), {})
        var reads = 0
        val owner = BudgetEnforcement(
            store,
            BudgetAlert { _, _ -> },
            HeadPerfHistory {
                PerfRowsSource {
                    reads++
                    PerfRowsWindow(emptyList())
                }
            },
            {},
            WallClock { SEED_BOOT_MS },
            BudgetSeedRuntime(backgroundScope, StandardTestDispatcher(testScheduler)),
        )
        owner.forHead("head", seedCatalog)
        val routes = BudgetRoutes(BudgetSource { store }, ConfigService(paths))
        val saved = routes.write("""{"budgets":[{"head":"head","daily_usd":50,"action":"warn"}]}""", owner)
        assertTrue(saved.body.contains("\"used_usd\":null"), saved.body)
        assertTrue(saved.body.contains("\"spend_pending\":true"), saved.body)
        assertEquals(0, reads, "the route does not pretend to have completed the historical read")
        runCurrent()
        val complete = routes.read(owner)
        assertTrue(complete.body.contains("\"used_usd\":0.0"), complete.body)
        assertTrue(complete.body.contains("\"remaining_usd\":50.0"), complete.body)
        assertTrue(complete.body.contains("\"spend_pending\":false"), complete.body)
        assertEquals(1, reads)
    }

    @Test
    fun `a persisted cap is known at construction and PUT publishes before a refresh`() = runTest {
        val file = directory.resolve("budgets.json")
        BudgetStore(file, {}).replace(listOf(Budget("head", 0.0, BudgetActions.BLOCK)))
        val store = BudgetStore(file, {})
        val owner = BudgetEnforcement(
            store,
            BudgetAlert { _, _ -> },
            emptyHistory(),
            {},
            WallClock { SEED_BOOT_MS },
            BudgetSeedRuntime(backgroundScope, StandardTestDispatcher(testScheduler)),
        )
        val head = owner.forHead("head", null)
        assertNotNull(head.admit(), "not yet refreshed cannot masquerade as no cap at startup")
        store.replace(listOf(Budget("head", 10.0, BudgetActions.BLOCK)))
        assertNull(head.admit(), "a saved cap is effective without waiting for background work")
    }

    @Test
    fun `a hand edited cap reaches admission within the declared background refresh bound`() = runTest {
        val file = directory.resolve("budgets.json")
        val store = BudgetStore(file, {})
        store.replace(listOf(Budget("head", 5.0, BudgetActions.BLOCK)))
        val owner = BudgetEnforcement(
            store,
            BudgetAlert { _, _ -> },
            emptyHistory(),
            {},
            WallClock { SEED_BOOT_MS },
            BudgetSeedRuntime(backgroundScope, StandardTestDispatcher(testScheduler)),
        )
        val head = owner.forHead("head", null)
        assertNull(head.admit())
        runCurrent()
        edit(file, Files.readString(file).replace("5.0", "0.0"))
        advanceTimeBy(POLICY_BOUND_MS)
        runCurrent()
        assertNotNull(head.admit(), "an external cap is visible by the refresh boundary without an admission read")
    }

    @Test
    fun `a broken hand edit preserves named fail open behavior rather than the old cap`() = runTest {
        val file = directory.resolve("budgets.json")
        val logs = mutableListOf<String>()
        val store = BudgetStore(file, { logs.add(it) })
        store.replace(listOf(Budget("head", 0.0, BudgetActions.BLOCK)))
        val owner = BudgetEnforcement(
            store,
            BudgetAlert { _, _ -> },
            emptyHistory(),
            {},
            WallClock { SEED_BOOT_MS },
            BudgetSeedRuntime(backgroundScope, StandardTestDispatcher(testScheduler)),
        )
        val head = owner.forHead("head", null)
        assertNotNull(head.admit())
        runCurrent()
        edit(file, "{ not json")
        advanceTimeBy(POLICY_BOUND_MS)
        runCurrent()
        assertNull(head.admit(), "V4-296 explicitly runs with no budget when the policy cannot parse")
        assertTrue(store.current().budgets.isEmpty())
        assertTrue(store.current().unreadable?.contains("could not be read") == true)
        advanceTimeBy(POLICY_BOUND_MS)
        runCurrent()
        assertEquals(1, logs.count { "could not be read" in it }, "one diagnostic per broken file version")
    }

    @Test
    fun `historical spend reaching a warn cap is announced when its seed lands`() = runTest {
        val store = BudgetStore(directory.resolve("budgets.json"), {})
        store.replace(listOf(Budget("head", 1.0, BudgetActions.WARN)))
        val alerts = mutableListOf<String>()
        val history = HeadPerfHistory {
            PerfRowsSource { PerfRowsWindow(listOf(PerfRow(SEED_BOOT_MS - 1, "ok", tokens(2), model = "priced"))) }
        }
        val owner = BudgetEnforcement(
            store,
            BudgetAlert { _, text -> alerts.add(text) },
            history,
            {},
            WallClock { SEED_BOOT_MS },
            BudgetSeedRuntime(backgroundScope, StandardTestDispatcher(testScheduler)),
        )
        val head = owner.forHead("head", seedCatalog)
        head.spent(SEED_BOOT_MS, "priced", tokens(0))
        assertNull(head.admit())
        assertTrue(alerts.isEmpty(), "warn-at-turn-end also leaves history on the background dispatcher")
        assertFalse(owner.spending("head")!!.complete)
        runCurrent()
        assertEquals(1, alerts.size, "history is not silently ignored when no later turn arrives")
        repeat(3) { assertNull(head.admit()) }
        assertEquals(1, alerts.size, "the seed and later admissions share warning deduplication")
    }

    @Test
    fun `an old pending seed cannot change the next day tally`() = runTest {
        val store = BudgetStore(directory.resolve("budgets.json"), {})
        store.replace(listOf(Budget("head", 5.0, BudgetActions.BLOCK)))
        var now = SEED_BOOT_MS
        val history = HeadPerfHistory {
            PerfRowsSource { PerfRowsWindow(listOf(PerfRow(SEED_BOOT_MS - 1, "ok", tokens(9), model = "priced"))) }
        }
        val owner = BudgetEnforcement(
            store,
            BudgetAlert { _, _ -> },
            history,
            {},
            WallClock { now },
            BudgetSeedRuntime(backgroundScope, StandardTestDispatcher(testScheduler)),
        )
        val head = owner.forHead("head", seedCatalog)
        assertNull(head.admit())
        assertFalse(owner.spending("head")!!.complete)
        now += SEED_DAY_MS
        assertNull(head.admit())
        runCurrent()
        assertEquals(BudgetSpend(0.0, 5.0, 0, true), owner.spending("head"))
    }

    private fun emptyHistory(): HeadPerfHistory = HeadPerfHistory { PerfRowsSource { PerfRowsWindow(emptyList()) } }

    private fun tokens(usd: Long): Map<String, Long> = mapOf(PerfKeys.IN_TOKENS to usd * 1_000_000L)

    private fun edit(file: Path, text: String) {
        val before = Files.getLastModifiedTime(file).toMillis()
        Files.writeString(file, text)
        Files.setLastModifiedTime(file, FileTime.fromMillis(before + POLICY_BOUND_MS))
    }
}
