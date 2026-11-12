package splice.observability.export

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfScope
import splice.core.util.PerfWorkBudget

internal data class ExportSeries(val metric: String, val scope: PerfScope)
private data class ExportMinute(val series: ExportSeries, val sourceScope: PerfScope, val from: Long)
private data class ExportCheckpoint(val point: PerfAggregatePoint, val lease: PerfWorkBudget.Lease)
private data class ExportTotal(var point: PerfAggregatePoint, val lease: PerfWorkBudget.Lease)

/** Worker-confined reconciliation: a scrape never advances this ledger. Expiry retains accumulated totals. */
internal class ExportTotals : AutoCloseable {
    private val minutes = LinkedHashMap<ExportMinute, ExportCheckpoint>()
    private val totals = LinkedHashMap<ExportSeries, ExportTotal>()
    var asOfMs: Long = 0L
        private set
    var missingMs: Long = 0L
        private set
    var dropped: Long = 0L
        private set

    fun reconcile(snapshot: PerfAggregateSnapshot) {
        if (asOfMs > 0L && snapshot.fromMs > asOfMs) missingMs += snapshot.fromMs - asOfMs
        if (snapshot.untilMs < asOfMs) { dropped++; return }
        minutes.entries.removeIf { (_, value) ->
            (value.point.fromMs < snapshot.fromMs).also { if (it) value.lease.close() }
        }
        snapshot.points.forEach(::add)
        asOfMs = snapshot.untilMs
    }

    fun points(): List<PerfAggregatePoint> = totals.values.map { it.point }.filter {
        ExportSchema.metrics[it.metric]?.type == "gauge" || it.count > 0
    }

    private fun add(raw: PerfAggregatePoint) {
        val metric = ExportSchema.metrics[raw.metric] ?: return
        if (metric.type == "legacy") return
        val point = raw.copy(scope = ExportSchema.scope(raw.scope), buckets = raw.buckets.toList())
        val series = ExportSeries(point.metric, point.scope)
        val total = totals[series] ?: reserve(series, point) ?: return
        if (metric.type == "gauge") {
            if (point.untilMs >= total.point.untilMs) total.point = point
        } else {
            reconcilePoint(ExportMinute(series, raw.scope, point.fromMs), point, total)
        }
    }

    private fun reconcilePoint(key: ExportMinute, point: PerfAggregatePoint, total: ExportTotal) {
        val old = minutes[key]
        val delta = ExportPopulation.difference(point, old?.point) ?: run { dropped++; return }
        val lease = old?.lease ?: if (minutes.size < MAX_MINUTES) {
            PerfWorkBudget.acquire(
                EXPORT_TOTAL_POINT_BYTES,
            )
        } else {
            null
        }
        if (lease == null) { dropped++; return }
        total.point = ExportPopulation.merge(total.point, delta)
        minutes[key] = ExportCheckpoint(point, lease)
    }

    private fun reserve(series: ExportSeries, point: PerfAggregatePoint): ExportTotal? {
        if (totals.size >= MAX_SERIES || totals.keys.count { it.metric == series.metric } >= FAMILY_SERIES) {
            dropped++
            return null
        }
        val lease = PerfWorkBudget.acquire(EXPORT_TOTAL_POINT_BYTES) ?: run { dropped++; return null }
        return ExportTotal(point.copy(count = 0, sum = 0.0, min = null, max = null, buckets = emptyList()), lease)
            .also { totals[series] = it }
    }

    override fun close() {
        minutes.values.forEach { it.lease.close() }
        totals.values.forEach { it.lease.close() }
        minutes.clear()
        totals.clear()
    }
}

// Charge 2 KiB for each retained cumulative point or minute checkpoint, including its dimensions and histogram.
private const val EXPORT_TOTAL_POINT_BYTES = 2_048L

// Bound reconciliation to 2,048 minute checkpoints so changing series cannot retain an unlimited deduplication history.
private const val MAX_MINUTES = 2_048

// Keep cumulative exporter state to 512 series even when the source rotates through new dimensions.
private const val MAX_SERIES = 512

// Limit one metric family to half the cumulative-series capacity so it cannot occupy every slot.
private const val FAMILY_SERIES = 256
