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
import splice.usage.perf.PerfTurnFacts
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZoneOffset

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

    private fun tokens(usd: Long): Map<String, Long> = mapOf(
        PerfKeys.IN_TOKENS to usd * 1_000_000L,
        PerfKeys.OUT_TOKENS to 0L,
        PerfKeys.CACHED_TOKENS to 0L,
        PerfKeys.CACHE_WRITE_TOKENS to 0L,
    )

    @Test
    fun `used and remaining read exactly the admission tally across repeated head wiring and UTC midnight`() {
        val store = BudgetStore(directory.resolve("budgets.json"))
        store.replace(listOf(Budget("native", 5.0, BudgetActions.BLOCK)))
        var now = SPEND_BOOT_MS
        val before = PerfRow(SPEND_BOOT_MS - 1, "ok", tokens(1), facts = PerfTurnFacts(model = "priced"))
        val history = HeadPerfHistory { PerfRowsSource { PerfRowsWindow(listOf(before)) } }
        val owner = BudgetEnforcement(
            store,
            BudgetAlert { _, _ -> },
            history,
            {},
            WallClock { now },
            immediateSeed(),
            ZoneOffset.UTC,
        )
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
            ZoneOffset.UTC,
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

    /** The count says "requests with no price", and the Requests page lists no local step, so a local step that has no
     *  price is not one of them; its dollars, when it has a price, are still spent. */
    @Test
    fun `a local step with no price is no unpriced request, and a priced one still spends`() {
        val store = BudgetStore(directory.resolve("budgets.json"))
        store.replace(listOf(Budget("head", 5.0, BudgetActions.WARN)))
        val ready = PerfRowsWindow(emptyList())
        val owner = BudgetEnforcement(
            store,
            BudgetAlert { _, _ -> },
            HeadPerfHistory { PerfRowsSource { ready } },
            {},
            WallClock { SPEND_BOOT_MS },
            immediateSeed(),
            ZoneOffset.UTC,
        )
        val head = owner.forHead("head", catalog)
        head.spent(SPEND_BOOT_MS, "unpriced", mapOf(PerfKeys.LOCAL_STEP to 1L))
        head.spent(SPEND_BOOT_MS, "priced", tokens(2) + (PerfKeys.LOCAL_STEP to 1L))

        val spend = owner.spending("head")!!
        assertEquals(0, spend.unpricedTurns)
        assertEquals(2.0, spend.usedUsd)

        head.spent(SPEND_BOOT_MS, "unpriced", emptyMap())
        assertEquals(1, owner.spending("head")!!.unpricedTurns)
    }

    @Test
    fun `a skipped history row or posted source without usage cannot become a measured zero`() {
        val samples = listOf(
            PerfRowsWindow(emptyList(), skipped = 1),
            PerfRowsWindow(
                listOf(
                    PerfRow(
                        ts = SPEND_BOOT_MS - 1,
                        outcome = "failure:api_error",
                        fields = mapOf(PerfKeys.UPSTREAM_REQ_BYTES to 32L),
                        facts = PerfTurnFacts(model = "priced"),
                    ),
                ),
            ),
        )
        samples.forEachIndexed { index, sample ->
            val store = BudgetStore(directory.resolve("budget-$index.json"))
            store.replace(listOf(Budget("head", 50.0, BudgetActions.WARN)))
            val owner = BudgetEnforcement(
                store,
                BudgetAlert { _, _ -> },
                HeadPerfHistory { PerfRowsSource { sample } },
                {},
                WallClock { SPEND_BOOT_MS },
                immediateSeed(),
                ZoneOffset.UTC,
            )
            owner.forHead("head", catalog)
            val spend = owner.spending("head")!!
            assertNull(spend.usedUsd, "unreported or missing history is not a zero-dollar day")
            assertNull(spend.remainingUsd)
            assertFalse(spend.complete)
            assertFalse(spend.pending, "unreadable is not a still-running history read")
        }
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
            ZoneOffset.UTC,
        )
        owner.forHead("native", catalog).spent(SPEND_BOOT_MS, "priced", tokens(2))
        val route = BudgetRoutes(BudgetSource { store }, ConfigService(paths))
        val reply = route.read(owner)
        assertTrue(reply.body.contains("\"used_usd\":2.0"))
        assertTrue(reply.body.contains("\"remaining_usd\":3.0"))
        assertFalse(reply.body.contains("account"))
        assertTrue(route.read().body.contains("\"used_usd\":null"))
    }

    @Test
    fun `the budgets reply says when the budget day next rolls over, drawn in the ledger's own zone`() {
        val store = BudgetStore(directory.resolve("budgets.json"))
        val paths = StatePaths(baseOverride = directory.resolve("state"))
        val history = HeadPerfHistory { PerfRowsSource { PerfRowsWindow(emptyList()) } }
        // 01:00 UTC on Oct 4, 2024, which is still Oct 3 in Chicago: the two zones name different next midnights.
        val at = 20_000L * SPEND_DAY_MS + 3_600_000L
        val route = BudgetRoutes(BudgetSource { store }, ConfigService(paths))

        fun resetIn(zone: ZoneId): Long {
            val owner = BudgetEnforcement(
                store,
                BudgetAlert { _, _ -> },
                history,
                {},
                WallClock { at },
                immediateSeed(),
                zone,
            )
            return owner.dayResetsAtMs()
        }

        val utc = resetIn(ZoneOffset.UTC)
        val chicago = resetIn(ZoneId.of("America/Chicago"))
        assertEquals(20_001L * SPEND_DAY_MS, utc, "UTC rolls at the next UTC midnight")
        assertEquals(
            20_000L * SPEND_DAY_MS + 5 * 3_600_000L,
            chicago,
            "Chicago rolls five hours later, still the same UTC day",
        )
        assertTrue(
            route.read(owner(store, history, at, ZoneId.of("America/Chicago"))).body
                .contains("\"day_resets_at_epoch_ms\":"),
            "the reply carries the ledger's boundary, not the machine's",
        )
        assertTrue(route.read().body.contains("\"day_resets_at_epoch_ms\":null"), "nothing wired names no boundary")
    }

    // Re-review, Oct 10: the page derived today's start as tomorrow's midnight minus 24 hours, which is wrong on the
    // two days a year the clock changes. The daemon names the start itself, from the calendar day.
    @Test
    fun `the day starts at the calendar day's own midnight, on the 25-hour and the 23-hour days too`() {
        val store = BudgetStore(directory.resolve("budgets.json"))
        val history = HeadPerfHistory { PerfRowsSource { PerfRowsWindow(emptyList()) } }
        val chicago = ZoneId.of("America/Chicago")

        fun startIn(zone: ZoneId, at: java.time.ZonedDateTime) =
            owner(store, history, at.toInstant().toEpochMilli(), zone).dayStartedAtMs()

        fun ms(zone: ZoneId, y: Int, m: Int, d: Int) =
            java.time.ZonedDateTime.of(y, m, d, 0, 0, 0, 0, zone).toInstant().toEpochMilli()

        // Nov 1, 2026 is 25 hours long in Chicago; 00:10 CT that day is inside it, and 24 hours before the next
        // midnight (Nov 2 00:00 CST) would be Nov 1 01:00 CDT-equivalent, an hour too late.
        val nov = java.time.ZonedDateTime.of(2026, 11, 1, 0, 10, 0, 0, chicago)
        assertEquals(ms(chicago, 2026, 11, 1), startIn(chicago, nov))

        // Mar 8, 2026 is 23 hours long: the start is still that day's midnight, not an hour of the day before.
        val mar = java.time.ZonedDateTime.of(2026, 3, 8, 12, 0, 0, 0, chicago)
        assertEquals(ms(chicago, 2026, 3, 8), startIn(chicago, mar))

        // A half-hour zone: the start sits inside a UTC hour, which is why the page counts whole hours only.
        val kolkata = ZoneId.of("Asia/Kolkata")
        val start = startIn(kolkata, java.time.ZonedDateTime.of(2026, 10, 10, 9, 0, 0, 0, kolkata))
        assertEquals(30 * 60_000L, start % 3_600_000L, "midnight in Kolkata is :30 past a UTC hour")
    }

    @Test
    fun `the budget reply names the day's start beside its reset`() {
        val store = BudgetStore(directory.resolve("budgets.json"))
        val history = HeadPerfHistory { PerfRowsSource { PerfRowsWindow(emptyList()) } }
        val config = ConfigService(StatePaths(baseOverride = directory.resolve("state")))
        val route = BudgetRoutes(BudgetSource { store }, config)
        val body = route.read(owner(store, history, SPEND_BOOT_MS, ZoneOffset.UTC)).body
        assertTrue(body.contains("\"day_started_at_epoch_ms\":${20_000L * SPEND_DAY_MS}"), body)
    }

    private fun owner(store: BudgetStore, history: HeadPerfHistory, at: Long, zone: ZoneId) = BudgetEnforcement(
        store,
        BudgetAlert { _, _ -> },
        history,
        {},
        WallClock { at },
        immediateSeed(),
        zone,
    )
}
