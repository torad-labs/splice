// NEW: V4-133 review — the budgets GET/PUT /api/budgets stores, ENFORCED per head and per UTC day.
//
// THE PROMISE, and all of it: "`warn` tells the operator, `block` refuses the turn"
// (console/src/entities/budget/model/types.ts). Before this file a PUT with `block` answered 200 and
// every turn on that head kept being served, because nothing on the turn path read a budget. Each head
// is handed ONE ledger ([BudgetEnforcement.forHead], through HeadServerFactory): admit() before a
// turn, spent() after its perf row. `block` refuses the turn once today's spend has reached the limit;
// `warn` tells the operator once, through the saved alert webhook and the head's log, and serves it.
//
// TODAY IS THE UTC DAY, the boundary /api/projects' cost_today_usd already draws, so the console's
// figure and the budget weigh the same turns. A new day starts from nothing.
//
// THE LEDGER IS IN MEMORY, because the perf files it would otherwise re-read are up to 64 MiB a
// generation and admission runs on every turn. What memory cannot know is the spend a PREVIOUS daemon
// recorded earlier today, so a budgeted head reads its perf history ONCE: the rows from the UTC day
// start up to this daemon's boot. Every row at or after boot was written by this daemon and reached
// the ledger through spent(), so the cut at boot counts each turn exactly once. An unbudgeted head
// never reads anything.
//
// THE PRICE is the provider entry's rate card, bucketed exactly as PerfTally (/api/projects) and
// SessionCost (the statusline) bucket a row. A turn on a model with NO rate card has no price: it is
// not counted, and never silently — the head's log names the model once a day, and a refusal says how
// many of today's turns it could not count.
package splice.usage.budgets

import splice.core.budget.BudgetBlock
import splice.core.budget.HeadBudget
import splice.core.model.ModelCatalog
import splice.core.model.ModelRates
import splice.core.model.TokenBuckets
import splice.core.model.TokenCost
import splice.core.perf.PerfKeys
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.usage.perf.PerfRowsSource
import java.util.Locale

private const val DAY_MS = 86_400_000L

/** Where one head's perf rows are read back from: the files its PerfStats writes and archives. */
public fun interface HeadPerfHistory {
    public fun rowsFor(head: String): PerfRowsSource
}

/** How a reached `warn` budget tells the operator. Must return at once: it runs on the turn path. */
public fun interface BudgetAlert {
    public fun budgetReached(head: String, text: String)
}

public class BudgetEnforcement(
    private val budgets: BudgetStore,
    private val alert: BudgetAlert,
    private val history: HeadPerfHistory,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    /** Every perf row at or after this instant was written by this daemon (see the file header). */
    private val bootMs = clock()

    /** [head]'s ledger, pricing its turns against [catalog]'s rate cards. One per head: the key is
     *  bound here, so a head can never spend against another's budget. */
    public fun forHead(head: String, catalog: ModelCatalog?): HeadBudget =
        HeadLedger(head, TurnPrice(catalog), history.rowsFor(head))

    /** [head]'s budget, read live from the store the route writes, or null when it has none. */
    private fun limitOf(head: String): Limit? =
        budgets.budgets().firstOrNull { it.head == head }?.let { row -> row.dailyUsd?.let { Limit(it, row.action) } }

    private inner class HeadLedger(
        private val head: String,
        private val price: TurnPrice,
        private val rows: PerfRowsSource,
    ) : HeadBudget {
        private val lock = Any()
        private var today = DayTally(clock() / DAY_MS)

        override fun admit(): BudgetBlock? {
            val limit = limitOf(head) ?: return null
            val now = clock()
            val reached = seededTally(now)?.takeIf { it.usd >= limit.usd } ?: return null
            if (limit.action == BudgetActions.WARN) warnOnce(now, limit.usd)
            return reached.takeIf { limit.action == BudgetActions.BLOCK }?.let { block(it, limit.usd) }
        }

        override fun spent(atMs: Long, model: String, counters: Map<String, Long>) {
            val usd = price.usd(model, counters)
            record(atMs, usd)
            val limit = limitOf(head) ?: return
            if (usd == null) noteUnpriced(atMs, model)
            if (limit.action == BudgetActions.WARN && reached(atMs, limit.usd)) warnOnce(atMs, limit.usd)
        }

        private fun record(atMs: Long, usd: Double?) {
            synchronized(lock) {
                val tally = tallyAt(atMs) ?: return
                if (usd != null) tally.usd += usd else tally.unpriced += 1
            }
        }

        /** Names [model] in the head's log the first time today it could not be priced. */
        private fun noteUnpriced(atMs: Long, model: String) {
            if (synchronized(lock) { tallyAt(atMs)?.unpricedModels?.add(model) == true }) {
                log(
                    "[${LogSafe.str(head)}][budget] model ${LogSafe.str(model)} has no rate card: its turns are " +
                        "not counted against the daily budget\n",
                )
            }
        }

        private fun reached(atMs: Long, limit: Double): Boolean = (seededTally(atMs)?.usd ?: 0.0) >= limit

        /** Today's tally for [atMs], rolled forward on a new UTC day, or null for a row stamped on a
         *  day already gone (a turn that ended across midnight belongs to the day it was stamped).
         *  Called under [lock] only. */
        private fun tallyAt(atMs: Long): DayTally? {
            val day = atMs / DAY_MS
            if (day > today.day) today = DayTally(day)
            return today.takeIf { it.day == day }
        }

        /** [tallyAt], with the spend a previous daemon recorded earlier today folded in once. The
         *  history is read OUTSIDE the lock, so a slow disk never holds up another turn's admission;
         *  two first turns racing may both read it, and only one folds it in. */
        private fun seededTally(atMs: Long): DayTally? {
            val unseeded = synchronized(lock) { tallyAt(atMs)?.takeIf { !it.seeded } }
                ?: return synchronized(lock) { tallyAt(atMs) }
            val seed = beforeBoot(unseeded.day * DAY_MS)
            return synchronized(lock) {
                if (!unseeded.seeded) {
                    unseeded.usd += seed.usd
                    unseeded.unpriced += seed.unpriced
                    unseeded.seeded = true
                }
                tallyAt(atMs)
            }
        }

        /** The spend recorded from [dayStart] up to this daemon's boot. A day that began after boot
         *  was seen whole by this daemon, so nothing is read for it. */
        private fun beforeBoot(dayStart: Long): Spend {
            val spend = Spend()
            if (bootMs <= dayStart) return spend
            val window = Cancellables.runCatchingCancellable { rows.window(dayStart) }
                .onFailure { unread(it.toString()) }
                .getOrNull() ?: return spend
            window.readError?.let(::unread)
            window.rows.filter { it.ts in dayStart until bootMs }.forEach { row ->
                val usd = price.usd(row.model, row.fields)
                if (usd != null) spend.usd += usd else spend.unpriced += 1
            }
            return spend
        }

        /** Today's earlier spend is short by what could not be read, and the budget says so. */
        private fun unread(why: String) {
            log(
                "[${LogSafe.str(head)}][budget] today's spend before this daemon started is short: " +
                    "${LogSafe.str(why)}\n",
            )
        }

        private fun block(tally: DayTally, limit: Double): BudgetBlock {
            val uncounted = when (tally.unpriced) {
                0L -> ""
                1L -> " 1 turn today ran on a model with no rate card and is not counted."
                else -> " ${tally.unpriced} turns today ran on a model with no rate card and are not counted."
            }
            return BudgetBlock(
                message = "splice refused this turn: head '$head' has spent ${Usd.dollars(tally.usd)} today (UTC) " +
                    "against its ${Usd.dollars(limit)} daily budget, and the budget's action is block. New turns " +
                    "on this head are refused until 00:00 UTC; raise or clear the budget in the splice console " +
                    "to continue sooner.$uncounted",
                detail = "spent_usd=${Usd.amount(tally.usd)} limit_usd=${Usd.amount(limit)} " +
                    "unpriced_turns=${tally.unpriced}",
            )
        }

        /** Tells the operator the budget is reached, once per UTC day per limit: a raised or lowered
         *  limit that is reached again is a new fact, a second turn over the same one is not. */
        private fun warnOnce(atMs: Long, limit: Double) {
            val spentToday = synchronized(lock) {
                val tally = tallyAt(atMs)?.takeIf { it.warnedAt != limit } ?: return
                tally.warnedAt = limit
                tally.usd
            }
            val amounts = "spent_usd=${Usd.amount(spentToday)} limit_usd=${Usd.amount(limit)}"
            log("[${LogSafe.str(head)}][budget] WARN reached: ${LogSafe.str(amounts)}\n")
            val text = "splice: head '$head' reached its daily budget: ${Usd.dollars(spentToday)} spent today " +
                "(UTC) against ${Usd.dollars(limit)}. Its action is warn, so its turns continue."
            Cancellables.discard(
                Cancellables.runCatchingCancellable { alert.budgetReached(head, text) },
                "an alert is best-effort; the turn it reports on is served whatever the webhook does",
            )
        }
    }
}

/** A head's budget as enforcement reads it: only a row with a daily_usd is a budget at all. */
private data class Limit(val usd: Double, val action: String)

/** One UTC day's spend on one head. Mutated only under its ledger's lock. */
private class DayTally(val day: Long) {
    var usd: Double = 0.0
    var unpriced: Long = 0L
    var seeded: Boolean = false

    /** The limit the operator was last told about today, or null before the first warning. */
    var warnedAt: Double? = null

    /** The unpriced models already named in the log today. */
    val unpricedModels: MutableSet<String> = HashSet()
}

/** Spend read back from the history, before it is folded into a [DayTally]. */
private class Spend {
    var usd: Double = 0.0
    var unpriced: Long = 0L
}

/** One turn's USD at its model's rate card. The buckets are PerfTally's and SessionCost's: in_tokens
 *  INCLUDES both cache buckets (SessionCost.bucketsFor says why), so the fresh-input bucket is the
 *  difference, floored at 0. The provider entry's card only, the one /api/projects prices
 *  cost_today_usd with, so the budget and the console's figure agree. Null is "no card", not zero. */
private class TurnPrice(private val catalog: ModelCatalog?, private val cost: TokenCost = TokenCost()) {
    fun usd(model: String?, counters: Map<String, Long>): Double? {
        val rates = model?.let(::ratesFor) ?: return null
        val cached = counters[PerfKeys.CACHED_TOKENS] ?: 0L
        val written = counters[PerfKeys.CACHE_WRITE_TOKENS] ?: 0L
        val buckets = TokenBuckets(
            input = ((counters[PerfKeys.IN_TOKENS] ?: 0L) - cached - written).coerceAtLeast(0L),
            cacheRead = cached,
            cacheWrite = written,
            output = counters[PerfKeys.OUT_TOKENS] ?: 0L,
        )
        return cost.of(buckets, rates)
    }

    private fun ratesFor(model: String): ModelRates? {
        val c = catalog ?: return null
        val key = c.stripSuffixes(model)
        return cost.ratesFor(null, key, c.models.firstOrNull { c.stripSuffixes(it.id) == key }?.rates)
    }
}

/** USD as the refusal, the alert and the log print it: two decimals, never the locale's separator. */
private object Usd {
    fun dollars(usd: Double): String = "$" + amount(usd)

    fun amount(usd: Double): String = String.format(Locale.ROOT, "%.2f", usd)
}
