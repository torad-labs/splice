// NEW: V4-133 review — ONE head's budget ledger: admit() before a turn, spent() after its perf row.
//
// THE LEDGER IS IN MEMORY, because the perf files it would otherwise re-read are up to 64 MiB a
// generation and admission runs on every turn. What memory cannot know is the spend a PREVIOUS daemon
// recorded earlier today, so a budgeted head reads its perf history ONCE: the rows from the UTC day
// start up to this daemon's boot. Every row at or after boot was written by this daemon and reached
// the ledger through spent(), so the cut at boot counts each turn exactly once. An unbudgeted head
// never reads anything, and a day that began after boot was seen whole, so it reads nothing either.
//
// ONE LOCK per ledger guards its day and claims one seed. History reads run on owned background I/O,
// never the admission thread. Until that seed lands, the live tally is explicitly partial.
package splice.usage.budgets

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import splice.core.budget.BudgetBlock
import splice.core.budget.HeadBudget
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.usage.perf.PerfRowsSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** What every head's ledger shares, built once by [BudgetEnforcement]. */
internal data class LedgerContext(
    val policy: BudgetPolicy,
    val alert: BudgetAlert,
    val log: LogSink,
    val clock: WallClock,
    /** Every perf row at or after this instant was written by this daemon. */
    val bootMs: Long,
    val seed: BudgetSeedRuntime,
)

internal class BudgetLedger(
    private val head: String,
    private val price: TurnPrice,
    private val rows: PerfRowsSource,
    private val context: LedgerContext,
) : HeadBudget {
    private val lock = Any()
    private var today = DayTally(utcDay(context.clock()))

    override fun admit(): BudgetBlock? {
        val limit = limit() ?: return null
        val now = context.clock()
        val reached = seededTally(now)?.takeIf { it.usd >= limit.usd } ?: return null
        if (limit.action == BudgetActions.WARN) warnOnce(now, limit.usd)
        return reached.takeIf { limit.action == BudgetActions.BLOCK }?.let { BudgetText.refusal(head, it, limit.usd) }
    }

    override fun spent(atMs: Long, model: String, counters: Map<String, Long>) {
        val usd = measuredCost(model, counters)
        synchronized(lock) { tallyAt(atMs)?.add(usd) }
        val limit = limit() ?: return
        if (usd == null) noteUnpriced(atMs, model)
        if (limit.action == BudgetActions.WARN && reached(atMs, limit.usd)) warnOnce(atMs, limit.usd)
    }

    /** Read the exact enforcement ledger, never a second history total or a per-account attribution. */
    fun snapshot(): BudgetSpend {
        val now = context.clock()
        seededTally(now)
        return synchronized(lock) {
            val tally = tallyAt(now) ?: return@synchronized BudgetSpend(null, null, 0, false)
            val complete = tally.seeded && tally.historyReadable && tally.unpriced == 0L
            val used = tally.usd.takeIf { complete }
            val remaining = used?.let { spend -> limit()?.usd?.let { (it - spend).coerceAtLeast(0.0) } }
            BudgetSpend(used, remaining, tally.unpriced, complete, pending = tally.seeding)
        }
    }

    /** This head's budget, read live from the store the route writes, or null when it has none. */
    private fun limit(): Limit? {
        val row = context.policy.forHead(head) ?: return null
        return row.dailyUsd?.let { Limit(it, row.action) }
    }

    /** Names [model] in the head's log the first time today it could not be priced. */
    private fun noteUnpriced(atMs: Long, model: String) {
        if (synchronized(lock) { tallyAt(atMs)?.firstUnpriced(model) == true }) {
            context.log(
                "[${LogSafe.str(head)}][budget] model ${LogSafe.str(model)} has no complete price or token usage: " +
                    "its turns are not counted against the daily budget\n",
            )
        }
    }

    private fun reached(atMs: Long, limit: Double): Boolean = (seededTally(atMs)?.usd ?: 0.0) >= limit

    /** The UTC day [atMs] falls on, as an epoch day: the boundary /api/projects draws for cost_today_usd. */
    private fun utcDay(atMs: Long): Long = Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()

    /** Today's tally for [atMs], rolled forward on a new UTC day, or null for a row stamped on a day
     *  already gone (a turn that ended across midnight belongs to the day it was stamped). Called
     *  under [lock] only. */
    private fun tallyAt(atMs: Long): DayTally? {
        val day = utcDay(atMs)
        if (day > today.day) today = DayTally(day)
        return today.takeIf { it.day == day }
    }

    /** Return the current tally immediately and claim at most one background seed for its day. */
    private fun seededTally(atMs: Long): DayTally? {
        val unseeded = synchronized(lock) {
            val tally = tallyAt(atMs) ?: return@synchronized null
            val dayStart = LocalDate.ofEpochDay(tally.day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            if (!tally.seeded && context.bootMs <= dayStart) tally.seed(DayTally(tally.day))
            tally.takeIf { !it.seeded && !it.seeding }?.also { it.seeding = true }
        }
        if (unseeded != null) {
            context.log("[${LogSafe.str(head)}][budget] today's spend is partial while pre-boot history loads\n")
            context.seed.scope.launch(context.seed.dispatcher) {
                val before = beforeBoot(unseeded.day)
                currentCoroutineContext().ensureActive()
                synchronized(lock) { unseeded.seed(before) }
                val limit = limit()
                if (limit?.action == BudgetActions.WARN && reached(atMs, limit.usd)) warnOnce(atMs, limit.usd)
            }
        }
        return synchronized(lock) { tallyAt(atMs) }
    }

    /** The spend recorded on [day] before this daemon's boot. */
    private fun beforeBoot(day: Long): DayTally {
        val before = DayTally(day)
        val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        if (context.bootMs <= dayStart) return before
        val window = Cancellables.runCatchingCancellable { rows.window(dayStart) }
            .onFailure {
                before.historyReadable = false
                unread(it.toString())
            }
            .getOrNull() ?: return before
        window.readError?.let {
            before.historyReadable = false
            unread(it)
        }
        if (window.skipped > 0) {
            before.historyReadable = false
            unread("${window.skipped} historical request records could not be read")
        }
        window.rows.filter { it.ts in dayStart until context.bootMs }
            .forEach { before.add(measuredCost(it.model, it.fields)) }
        return before
    }

    /** Missing billing counters cannot turn a posted-but-unreported source into a zero-dollar request. */
    private fun measuredCost(model: String?, fields: Map<String, Long>): Double? =
        if (PerfKeys.IN_TOKENS in fields || PerfKeys.OUT_TOKENS in fields) price.usd(model, fields) else null

    /** Today's earlier spend is short by what could not be read, and the head's log says so. */
    private fun unread(why: String) {
        context.log(
            "[${LogSafe.str(head)}][budget] today's spend before this daemon started is short: " +
                "${LogSafe.str(why)}\n",
        )
    }

    /** Tells the operator the budget is reached, once per UTC day per limit: a raised or lowered limit
     *  that is reached again is a new fact, a second turn over the same one is not. */
    private fun warnOnce(atMs: Long, limit: Double) {
        val spentToday = synchronized(lock) {
            val tally = tallyAt(atMs) ?: return
            if (!tally.firstWarning(limit)) return
            tally.usd
        }
        val amounts = BudgetText.amounts(spentToday, limit)
        context.log("[${LogSafe.str(head)}][budget] WARN reached: ${LogSafe.str(amounts)}\n")
        Cancellables.discard(
            Cancellables.runCatchingCancellable {
                context.alert.budgetReached(head, BudgetText.warning(head, spentToday, limit))
            },
            "an alert is best-effort; the turn it reports on is served whatever the webhook does",
        )
    }
}
