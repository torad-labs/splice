// NEW: Oct 10, 2026 — the hours splice holds the turns for but has no hourly total for are built from
// those turns, so Usage reaches as far back as the request records do (Marlin: "If splice holds a
// turn, Usage counts it").
//
// THE GAP THIS CLOSES. The hourly rollup keeps sums written as each turn finished, trimmed to the
// history window; the request records are a separate, much larger file set with its own window. When
// the window widens, or when the rollup's file is younger than the records (a new install of a daemon
// that keeps more hours than the one before it), the rollup reaches back days less far than the turns
// on disk do. On this machine the day that happened the figures read: 8.0 days of hourly buckets, 15
// days of archived request records. The month view was drawn over the shorter one.
//
// AN HOUR IS BUILT ONLY WHEN IT CAN BE EXACT (Marlin). Three refusals, each for a reason a person
// would recognise as "then don't show me a number":
//  · the read was not whole — a read error, or any row the reader could not parse — so no hour in it
//    can be trusted to hold all its turns. Nothing is backfilled, and the next read tries again.
//  · the OLDEST hour the rows reach. A generation may begin mid-hour, because the rotation before the
//    archive existed discarded what came earlier, so that hour's sums would be a part read as a whole.
//  · an hour the rollup already has. It was summed from each turn's own usage as the turn finished,
//    which is closer to the truth than any rebuild; the recorded hour always wins.
//
// ONCE PER WINDOW EDGE. A successful pass closes the gap by writing the hours into the rollup's own
// file, so the next read is the file and not a scan of every archived generation in the window. It
// remembers the edge it closed the gap down to rather than that it ran: widening the window moves
// that edge back and opens a new gap, which is scanned when it opens and not at the next restart. A
// refused pass remembers nothing and is retried, because the refusal may be a write that had not
// settled yet.
package splice.app.sources

import splice.core.model.TurnPrice
import splice.core.perf.KeptHistory
import splice.core.util.WallClock
import splice.head.usage.EconomicsBucket
import kotlin.time.Duration.Companion.hours

private val BACKFILL_HOUR_MS = 1.hours.inWholeMilliseconds

/** Builds the hours the rollup is missing from the turns splice still holds. */
internal class EconomicsBackfill(
    private val perf: PerfRowsFileSource,
    private val price: TurnPrice,
    private val kept: KeptHistory,
    private val clock: WallClock,
) {
    private val rows = EconomicsFromRows(price)

    /** The earliest cutoff a pass has already covered, or null when none has. A moment and not a
     *  flag: a pass closes the gap down to the window's edge AS IT STOOD, and widening the window
     *  moves that edge further back and opens a new gap. */
    @Volatile
    private var filledFrom: Long? = null

    /**
     * The hours to add to [held], or empty when there are none to add, when the gap is already
     * closed, or when the rows cannot give an exact answer. [held] is what the rollup holds now.
     */
    fun missing(held: List<EconomicsBucket>): List<EconomicsBucket> {
        val from = kept.now().cutoffMs(clock()) ?: 0L
        val oldestHeld = held.minOfOrNull { it.hour }
        if (done(from, oldestHeld)) {
            cover(from)
            return emptyList()
        }
        val evidence = perf.economicsEvidence(from)
        if (evidence.work.readError != null || evidence.work.skipped != 0) return emptyList()
        cover(from)
        return built(evidence, from, oldestHeld)
    }

    /**
     * Whether a pass at [from] has nothing left to find.
     *
     * Two ways. The rollup already reaches that edge itself, so there is no gap and nothing to scan
     * the archived generations for. Or a pass has already covered that edge — ONCE PER EDGE, not
     * once per start: a wider window is an edge further back than any pass has covered, and the gap
     * it opens, between the hours the rollup reaches and the older ones the records still hold, is
     * scanned when it opens. The flag this replaced went down for good on the first pass, so
     * widening rebuilt nothing until the daemon restarted and the month view was drawn over the
     * shorter history (found in review, Oct 10, 2026).
     */
    private fun done(from: Long, oldestHeld: Long?): Boolean {
        if (oldestHeld != null && oldestHeld <= from) return true
        return from >= (filledFrom ?: return false)
    }

    /** Record that the gap is closed down to [from], keeping the earliest edge any pass reached. */
    private fun cover(from: Long) {
        filledFrom = minOf(filledFrom ?: from, from)
    }

    /** One bucket per hour of rows inside the gap, each priced at the cards splice holds now. */
    private fun built(
        evidence: EconomicsPerfEvidence,
        from: Long,
        oldestHeld: Long?,
    ): List<EconomicsBucket> {
        // The same rows ProbeEconomics expects a recorded hour to EQUAL, selected by the same
        // reader. An hour built from a different set of rows than the one that grades it reads as a
        // disagreement, and the console then answers Unavailable for that head's whole hourly
        // history rather than for the hour: a legacy probe left out, or a budget refusal that never
        // reached a provider left in, is enough to hide a month.
        val byHour = EconomicsRows().recorded(evidence)
            .groupBy { it.ts / BACKFILL_HOUR_MS * BACKFILL_HOUR_MS }
        val partial = byHour.keys.minOrNull()
        return byHour
            .filterKeys { hour -> hour != partial && hour >= from && (oldestHeld == null || hour < oldestHeld) }
            .map { (hour, hourRows) -> rows.bucket(hour, hourRows) }
            .sortedBy { it.hour }
    }
}
