package splice.core.perf.observation.aggregate

import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity

/** Detaches one bounded page and acknowledges only the generation durably written. */
internal fun interface PerfCheckpointCoverage {
    fun measure(view: PerfCells, from: Long, until: Long): PerfCoverage
}

internal class PerfCheckpointPages(
    private val identity: PerfIdentity,
    private val cells: PerfCells,
    private val gauges: PerfGaugeBook,
    private val timeline: PerfTimeline,
    private val coverage: PerfCheckpointCoverage,
) {
    private var pending: Pending? = null

    fun capture(hasObservations: Boolean): PerfAggregateSnapshot? {
        if (pending != null) return null
        if (!PerfMemoryBudget.beginSnapshot()) return null
        return try {
            val until = if (hasObservations) timeline.last + 1 else 0L
            cells.dirtyPages.nextMinute()?.let { captureMinute(it, until) }
        } finally {
            PerfMemoryBudget.endSnapshot()
        }
    }

    fun settle(page: PerfAggregateSnapshot, durable: Boolean) {
        val current = pending?.takeIf { it.snapshot === page } ?: return
        cells.settlePage(current.minute, current.generation, durable)
        pending = null
    }

    fun clear() {
        pending = null
    }

    private fun captureMinute(minute: Long, until: Long): PerfAggregateSnapshot? {
        val generation = cells.dirtyPages.generation(minute) ?: return null
        val pageUntil = minOf(minute + PerfObservationLimits.AGGREGATE_MINUTE_MS, until)
        val copy = cells.copyMinute(minute)
        if (pageUntil > minute) gauges.previewMinute(copy, minute, pageUntil)
        val points = copy.points(pageUntil)
        if (points.isEmpty()) {
            cells.settlePage(minute, generation, durable = false, empty = true)
            return null
        }
        val snapshot = PerfAggregateSnapshot(
            identity,
            minute,
            pageUntil,
            points,
            coverage.measure(copy, minute, pageUntil),
        )
        pending = Pending(snapshot, minute, generation)
        return snapshot
    }

    private data class Pending(
        val snapshot: PerfAggregateSnapshot,
        val minute: Long,
        val generation: Long,
    )
}
