package splice.app.sources.observability

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfIdentity
import java.io.IOException

/** Adaptive streaming fold. Coarsening merges counts/buckets, never averages percentiles or drops old cells. */
internal class PerfReadFold(initialResolution: Long, private val span: Long) {
    var resolution = initialResolution
        private set
    private var collapsed = false
    private var bytes = 0L
    private var buckets = LinkedHashMap<ReadBucketKey, PerfReadBucket>()

    fun add(snapshot: PerfAggregateSnapshot) {
        ensure(snapshot.identity, snapshot.fromMs, null)
        buckets.getValue(key(snapshot.identity, snapshot.fromMs)).coverage(snapshot)
        for (point in snapshot.points) {
            ensure(snapshot.identity, snapshot.fromMs, point)
            val bucket = buckets.getValue(key(snapshot.identity, snapshot.fromMs))
            val before = bucket.bytes
            bucket.point(point)
            bytes += bucket.bytes - before
        }
    }

    fun snapshots(): List<PerfAggregateSnapshot> = buckets.values.sortedBy { it.fromMs() }.map { it.snapshot() }

    private fun ensure(identity: PerfIdentity, from: Long, point: PerfAggregatePoint?) {
        while (bytes + additional(identity, from, point) > FOLD_BYTES) {
            if (!coarsen()) throw IOException("query_memory_limit")
        }
        val key = key(identity, from)
        if (key !in buckets) {
            val bucket = PerfReadBucket(identity)
            buckets[key] = bucket
            bytes += bucket.bytes
        }
    }

    private fun additional(identity: PerfIdentity, from: Long, point: PerfAggregatePoint?): Long {
        val bucket = buckets[key(identity, from)]
        val metadata = if (bucket == null) BUCKET_OVERHEAD + PerfHistorySize.identity(identity) else 0L
        val cell = point?.let { bucket?.additional(it) ?: (PerfHistorySize.point(it) + CELL_OVERHEAD) } ?: 0L
        return metadata + cell
    }

    private fun coarsen(): Boolean {
        if (collapsed) return false
        val next = nextResolution()
        collapsed = next >= span
        resolution = if (collapsed) maxOf(span, resolution) else next
        val previous = buckets
        buckets = LinkedHashMap()
        bytes = 0L
        for (bucket in previous.values) rebin(bucket)
        return true
    }

    private fun nextResolution(): Long = when {
        resolution < FIVE_MINUTES -> FIVE_MINUTES
        resolution < FIFTEEN_MINUTES -> FIFTEEN_MINUTES
        resolution < PERF_HOUR_MS -> PERF_HOUR_MS
        resolution < SIX_HOURS -> SIX_HOURS
        resolution < PERF_DAY_MS -> PERF_DAY_MS
        else -> resolution * DAYS_PER_WEEK
    }

    private fun rebin(value: PerfReadBucket) {
        val key = key(value.identity, value.fromMs())
        val existing = buckets[key]
        if (existing == null) {
            buckets[key] = value
            bytes += value.bytes
        } else {
            val before = existing.bytes
            existing.absorb(value)
            bytes += existing.bytes - before
        }
    }

    private fun key(identity: PerfIdentity, from: Long): ReadBucketKey =
        ReadBucketKey(identity, if (collapsed) 0L else Math.floorDiv(from, resolution))
}

private data class ReadBucketKey(val identity: PerfIdentity, val slot: Long)
private const val FOLD_BYTES = 3L * 1_024 * 1_024
private const val CELL_OVERHEAD = 256L
private const val FIVE_MINUTES = 5L * PERF_MINUTE_MS
private const val FIFTEEN_MINUTES = 15L * PERF_MINUTE_MS
private const val SIX_HOURS = 6L * PERF_HOUR_MS
private const val DAYS_PER_WEEK = 7L
