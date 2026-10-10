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
// ONCE PER START. A successful pass closes the gap by writing the hours into the rollup's own file, so
// the next read is the file and not a scan of every archived generation in the window. A refused pass
// leaves the flag down and is retried, because the refusal may be a write that had not settled yet.
package splice.app.sources

import splice.core.model.TurnPrice
import splice.core.perf.HistoryWindow
import splice.core.util.WallClock
import splice.head.usage.EconomicsBucket
import kotlin.time.Duration.Companion.hours

private val BACKFILL_HOUR_MS = 1.hours.inWholeMilliseconds

/** Builds the hours the rollup is missing from the turns splice still holds. */
internal class EconomicsBackfill(
    private val perf: PerfRowsFileSource,
    private val price: TurnPrice,
    private val window: HistoryWindow,
    private val clock: WallClock,
) {
    private val rows = EconomicsFromRows(price)

    @Volatile
    private var filled = false

    /**
     * The hours to add to [held], or empty when there are none to add, when the gap is already
     * closed, or when the rows cannot give an exact answer. [held] is what the rollup holds now.
     */
    fun missing(held: List<EconomicsBucket>): List<EconomicsBucket> {
        val from = window.cutoffMs(clock()) ?: 0L
        val oldestHeld = held.minOfOrNull { it.hour }
        // Once per start, and over for good once the rollup reaches the window's edge itself: there
        // is no gap then, and nothing to scan the archived generations for.
        val closed = oldestHeld != null && oldestHeld <= from
        if (filled || closed) {
            filled = true
            return emptyList()
        }
        val evidence = perf.economicsEvidence(from)
        if (evidence.work.readError != null || evidence.work.skipped != 0) return emptyList()
        filled = true
        return built(evidence, from, oldestHeld)
    }

    /** One bucket per hour of rows inside the gap, each priced at the cards splice holds now. */
    private fun built(
        evidence: EconomicsPerfEvidence,
        from: Long,
        oldestHeld: Long?,
    ): List<EconomicsBucket> {
        val probes = evidence.probes.toSet()
        val byHour = evidence.work.rows.filterNot { it in probes }
            .groupBy { it.ts / BACKFILL_HOUR_MS * BACKFILL_HOUR_MS }
        val partial = byHour.keys.minOrNull()
        return byHour
            .filterKeys { hour -> hour != partial && hour >= from && (oldestHeld == null || hour < oldestHeld) }
            .map { (hour, hourRows) -> rows.bucket(hour, hourRows) }
            .sortedBy { it.hour }
    }
}
