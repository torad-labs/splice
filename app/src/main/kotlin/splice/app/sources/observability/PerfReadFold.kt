package splice.app.sources.observability

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfIdentity

/**
 * Adaptive streaming fold. Coarsening merges counts/buckets, never averages percentiles or drops old cells: first in
 * time, down to one bucket for the whole span, then across boots, whose points a summary adds together anyway.
 *
 * Coarsened as far as it goes, it keeps adding to every series it already holds and turns away only a series it has
 * not met, which [refused] counts. Until Nov 11, 2026 it stopped the whole read there, and the files after that
 * point, the newest, went unread: a day on a desk that had restarted ten times returned five of its hours.
 */
internal class PerfReadFold(initialResolution: Long, private val span: Long) {
    var resolution = initialResolution
        private set
    var refused = 0L
        private set
    private var collapsed = false
    private var bootsMerged = false
    private var bytes = 0L
    private var buckets = LinkedHashMap<ReadBucketKey, PerfReadBucket>()

    fun add(snapshot: PerfAggregateSnapshot) {
        if (!fits(snapshot.identity, snapshot.fromMs, null)) {
            refused += snapshot.points.size
            return
        }
        open(snapshot.identity, snapshot.fromMs).coverage(snapshot)
        for (point in snapshot.points) {
            if (!fits(snapshot.identity, snapshot.fromMs, point)) {
                refused++
                continue
            }
            val bucket = open(snapshot.identity, snapshot.fromMs)
            val before = bucket.bytes
            bucket.point(point)
            bytes += bucket.bytes - before
        }
    }

    fun snapshots(): List<PerfAggregateSnapshot> = buckets.values.sortedBy { it.fromMs() }.flatMap { it.snapshots() }

    /** Coarsens until [point], or with none its bucket, fits. False once nothing coarser is left and it does not. */
    private fun fits(identity: PerfIdentity, from: Long, point: PerfAggregatePoint?): Boolean {
        while (true) {
            val extra = additional(identity, from, point)
            // A series the fold already holds costs nothing more, so its counts are never turned away.
            if (extra == 0L || bytes + extra <= FOLD_BYTES) return true
            if (!coarsen()) return false
        }
    }

    private fun open(identity: PerfIdentity, from: Long): PerfReadBucket {
        val key = key(identity, from)
        val bucket = buckets.getOrPut(key) { PerfReadBucket(identity).also { bytes += it.bytes } }
        val before = bucket.bytes
        bucket.carry(identity)
        bytes += bucket.bytes - before
        return bucket
    }

    private fun additional(identity: PerfIdentity, from: Long, point: PerfAggregatePoint?): Long {
        val bucket = buckets[key(identity, from)]
        val metadata = bucket?.additional(identity) ?: (BUCKET_OVERHEAD + PerfHistorySize.identity(identity))
        val cell = point?.let { bucket?.additional(it) ?: (PerfHistorySize.held(it) + PERF_READ_CELL_OVERHEAD) } ?: 0L
        return metadata + cell
    }

    private fun coarsen(): Boolean {
        when {
            bootsMerged -> return false
            collapsed -> bootsMerged = true
            else -> {
                val next = nextResolution()
                collapsed = next >= span
                resolution = if (collapsed) maxOf(span, resolution) else next
            }
        }
        val previous = buckets
        buckets = LinkedHashMap()
        bytes = 0L
        val iterator = previous.values.iterator()
        while (iterator.hasNext()) {
            val bucket = iterator.next()
            iterator.remove()
            rebin(bucket)
        }
        return true
    }

    private fun nextResolution(): Long = when {
        resolution < FOLD_FIVE_MINUTES -> FOLD_FIVE_MINUTES
        resolution < FOLD_FIFTEEN_MINUTES -> FOLD_FIFTEEN_MINUTES
        resolution < PERF_HOUR_MS -> PERF_HOUR_MS
        resolution < SIX_HOURS -> SIX_HOURS
        resolution < PERF_DAY_MS -> PERF_DAY_MS
        else -> resolution * COARSENING_FACTOR
    }

    private fun rebin(value: PerfReadBucket) {
        val key = key(value.identity(), value.fromMs())
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
        ReadBucketKey(identity.takeUnless { bootsMerged }, if (collapsed) 0L else Math.floorDiv(from, resolution))
}

/** [identity] is null once the fold has merged boots: one bucket then holds every boot's points for its slot. */
private data class ReadBucketKey(val identity: PerfIdentity?, val slot: Long)

// Coarsen when aggregate cells exceed 2 MiB instead of dropping counts or retaining every source point.
private const val FOLD_BYTES = 2L * 1_024 * 1_024
private const val FOLD_FIVE_MINUTES = 5L * PERF_MINUTE_MS
private const val FOLD_FIFTEEN_MINUTES = 15L * PERF_MINUTE_MS
private const val SIX_HOURS = 6L * PERF_HOUR_MS

// After day-sized buckets, multiply resolution by seven on each coarsening step until the selected span fits.
private const val COARSENING_FACTOR = 7L
