// NEW: V4-133 review — the budgets the console saves are ENFORCED, per head and per UTC day.
//
// The console's own contract (console/src/entities/budget/model/types.ts): "`warn` tells the
// operator, `block` refuses the turn". Before this row a PUT /api/budgets with `block` answered 200
// and every turn on that head kept being served. Each test drives the ledger a head is handed the way
// the head drives it — admit() before a turn, spent() after its perf row — and asserts on the refusal,
// the alert and the arithmetic, never only that something happened.
package splice.usage.budgets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.perf.PerfKeys
import splice.core.util.WallClock
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L

/** 2024-10-04 00:00 UTC — any UTC midnight; the tests only move relative to it. */
private const val DAY_START = 20_000L * DAY_MS
private const val TOKENS_PER_USD = 1_000_000.0
private const val MODEL = "m1"

/** One USD per million fresh input tokens, a tenth of that for a cache read. */
private val CATALOG = ModelCatalog(
    discoveryPrefix = "claude-test--",
    models = listOf(ModelEntry(MODEL, contextWindow = 200_000, rates = ModelRates(1.0, 0.1, 4.0))),
    defaultContextWindow = 200_000,
)

/** A turn's counters worth [usd] of fresh input at [CATALOG]'s rate. */
private fun turnOf(usd: Double): Map<String, Long> = mapOf(PerfKeys.IN_TOKENS to (usd * TOKENS_PER_USD).toLong())

private class FakeHistory(private val rows: List<PerfRow> = emptyList()) : HeadPerfHistory {
    val asked = CopyOnWriteArrayList<Long>()

    override fun rowsFor(head: String): PerfRowsSource = PerfRowsSource { since ->
        asked.add(since)
        PerfRowsWindow(rows.filter { it.ts >= since })
    }
}

private class Rig(tmp: Path, history: FakeHistory = FakeHistory(), startMs: Long = DAY_START + 10 * HOUR_MS) {
    var now: Long = startMs
    val store = BudgetStore(tmp.resolve("budgets.json"))
    val alerts = CopyOnWriteArrayList<Pair<String, String>>()
    val logs = CopyOnWriteArrayList<String>()
    val enforcement = BudgetEnforcement(
        store,
        BudgetAlert { head, text -> alerts.add(head to text) },
        history,
        { logs.add(it) },
        WallClock { now },
    )

    fun budget(head: String, dailyUsd: Double?, action: String) {
        store.replace(listOf(Budget(head, dailyUsd, action)))
    }
}

class BudgetEnforcementTest {

    @Test
    fun `a block budget admits turns under the limit and refuses them once today's spend reaches it`(
        @TempDir tmp: Path,
    ) {
        val rig = Rig(tmp)
        rig.budget("h", 2.0, BudgetActions.BLOCK)
        val head = rig.enforcement.forHead("h", CATALOG)

        assertNull(head.admit(), "nothing spent yet")
        head.spent(rig.now, MODEL, turnOf(1.0))
        assertNull(head.admit(), "$1.00 of a $2.00 budget is under it")
        head.spent(rig.now, MODEL, turnOf(1.0))

        assertNotNull(head.admit(), "$2.00 spent reaches a $2.00 block budget: the turn is refused")
        assertEquals(emptyList<Pair<String, String>>(), rig.alerts.toList(), "block refuses; it does not alert")
    }

    @Test
    fun `the refusal names the head, the spend, the limit and when it lifts, and a new UTC day lifts it`(
        @TempDir tmp: Path,
    ) {
        val rig = Rig(tmp)
        rig.budget("h", 2.0, BudgetActions.BLOCK)
        val head = rig.enforcement.forHead("h", CATALOG)
        head.spent(rig.now, MODEL, turnOf(2.5))

        val block = head.admit()
        assertNotNull(block)
        val message = block!!.message
        assertTrue(message.contains("'h'"), message)
        assertTrue(message.contains("$2.50 today"), message)
        assertTrue(message.contains("$2.00 daily budget"), message)
        assertTrue(message.contains("00:00 UTC"), message)
        assertEquals("spent_usd=2.50 limit_usd=2.00 unpriced_turns=0", block.detail)

        rig.now = DAY_START + DAY_MS + 1
        assertNull(head.admit(), "the budget is per UTC day: the next day starts from nothing")
    }

    @Test
    fun `a head with no budget, or a null daily_usd, is never refused and never reads its history`(
        @TempDir tmp: Path,
    ) {
        val history = FakeHistory(listOf(PerfRow(DAY_START + 1, "ok", turnOf(50.0), model = MODEL)))
        val rig = Rig(tmp, history)
        rig.budget("other", 0.0, BudgetActions.BLOCK)
        val unbudgeted = rig.enforcement.forHead("h", CATALOG)
        unbudgeted.spent(rig.now, MODEL, turnOf(100.0))
        assertNull(unbudgeted.admit(), "a head with no row has no budget")

        rig.budget("h", null, BudgetActions.BLOCK)
        assertNull(unbudgeted.admit(), "a null daily_usd is no budget, not zero")
        assertEquals(emptyList<Long>(), history.asked.toList(), "an unbudgeted head must never pay for a history read")
    }

    @Test
    fun `spend a previous daemon recorded today counts once, and yesterday's does not count`(@TempDir tmp: Path) {
        val boot = DAY_START + 10 * HOUR_MS
        val history = FakeHistory(
            listOf(
                PerfRow(DAY_START - HOUR_MS, "ok", turnOf(50.0), model = MODEL),
                PerfRow(DAY_START + HOUR_MS, "ok", turnOf(1.5), model = MODEL),
                // Written by THIS daemon after boot: it reaches the ledger through spent(), below.
                PerfRow(boot + HOUR_MS, "ok", turnOf(1.0), model = MODEL),
            ),
        )
        val rig = Rig(tmp, history, startMs = boot)
        rig.budget("h", 3.0, BudgetActions.BLOCK)
        val head = rig.enforcement.forHead("h", CATALOG)

        rig.now = boot + HOUR_MS
        head.spent(rig.now, MODEL, turnOf(1.0))
        assertNull(head.admit(), "1.50 before boot + 1.00 since = 2.50, under 3.00; counting a row twice reads 3.50")
        assertEquals(listOf(DAY_START), history.asked.toList(), "today's rows are read once, from the UTC day start")

        head.spent(rig.now, MODEL, turnOf(0.5))
        assertNotNull(head.admit(), "3.00 reached, the pre-boot spend included")
    }

    @Test
    fun `a warn budget tells the operator once when reached, never refuses, and tells again on a new limit or day`(
        @TempDir tmp: Path,
    ) {
        val rig = Rig(tmp)
        rig.budget("h", 1.0, BudgetActions.WARN)
        val head = rig.enforcement.forHead("h", CATALOG)

        head.spent(rig.now, MODEL, turnOf(0.5))
        assertNull(head.admit())
        assertEquals(0, rig.alerts.size, "under the budget nothing is said")

        head.spent(rig.now, MODEL, turnOf(0.5))
        assertNull(head.admit(), "warn never refuses a turn")
        head.spent(rig.now, MODEL, turnOf(0.5))
        assertNull(head.admit())
        assertEquals(1, rig.alerts.size, "reaching the budget is told ONCE, not per turn: ${rig.alerts}")
        val (alertedHead, text) = rig.alerts.single()
        assertEquals("h", alertedHead)
        assertTrue(text.contains("'h'"), text)
        assertTrue(text.contains("$1.00 spent today"), text)
        assertTrue(rig.logs.any { it.startsWith("[h][budget]") && it.contains("limit_usd=1.00") }, "${rig.logs}")

        rig.budget("h", 2.0, BudgetActions.WARN)
        head.spent(rig.now, MODEL, turnOf(0.5))
        assertEquals(2, rig.alerts.size, "a raised limit that is reached again is a new fact: ${rig.alerts}")

        rig.now = DAY_START + DAY_MS + HOUR_MS
        head.spent(rig.now, MODEL, turnOf(2.0))
        assertEquals(3, rig.alerts.size, "a new UTC day reached again is told again: ${rig.alerts}")
    }

    @Test
    fun `cached input bills at the read rate, and a turn with no rate card is not counted but is named`(
        @TempDir tmp: Path,
    ) {
        val rig = Rig(tmp)
        rig.budget("h", 0.25, BudgetActions.BLOCK)
        val head = rig.enforcement.forHead("h", CATALOG)

        // in_tokens INCLUDES the cached portion (SessionCost.bucketsFor): 2M cached is $0.20, not $2.20.
        head.spent(rig.now, MODEL, mapOf(PerfKeys.IN_TOKENS to 2_000_000L, PerfKeys.CACHED_TOKENS to 2_000_000L))
        assertNull(head.admit(), "a cache read billed as fresh input would refuse this turn")

        head.spent(rig.now, "no-card", turnOf(100.0))
        assertNull(head.admit(), "a model with no rate card has no price to count")
        assertTrue(rig.logs.any { it.startsWith("[h][budget]") && it.contains("no-card") }, "${rig.logs}")

        head.spent(rig.now, MODEL, turnOf(0.1))
        val block = head.admit()
        assertNotNull(block)
        assertTrue(block!!.message.contains("1 turn today ran on a model with no rate card"), block.message)
    }
}
