// NEW: bounded primitive age metadata; an overflow never refuses heap admission.
package splice.core.memory

import splice.core.util.MonoClock

// why: 4096 allocation ages cover ordinary concurrent owners with a fixed 64 KiB primitive metadata budget.
internal const val HEAP_AGE_CAPACITY: Int = 4096

// why: the tracked_1s_to_1m diagnostic bucket ends at one minute in monotonic milliseconds.
private const val AGE_MINUTE_MS = 60_000L

// why: the tracked_1m_to_5m diagnostic bucket ends at five minutes in the same monotonic time unit.
private const val AGE_FIVE_MINUTES_MS = 300_000L

// why: the tracked_5m_to_1h diagnostic bucket ends at one hour before the tracked_over_1h bucket begins.
private const val AGE_HOUR_MS = 3_600_000L

/** All access is under the process ledger lock. No slot stores an allocation, lease or owner reference. */
internal class HeapAges {
    private enum class Bucket { UNDER_SECOND, UNDER_MINUTE, UNDER_FIVE_MINUTES, UNDER_HOUR, OLDER }

    private val categories = IntArray(HEAP_AGE_CAPACITY) { -1 }
    private val started = LongArray(HEAP_AGE_CAPACITY)
    private val free = IntArray(HEAP_AGE_CAPACITY) { it }
    private var freeCount = HEAP_AGE_CAPACITY
    private val overflows = LongArray(HeapAccounting.entries.size * HeapCategory.entries.size)

    fun acquire(index: Int, startedAtMs: Long): Int {
        if (freeCount == 0) {
            if (overflows[index] < Long.MAX_VALUE) overflows[index]++
            return -1
        }
        val slot = free[--freeCount]
        categories[slot] = index
        started[slot] = startedAtMs
        return slot
    }

    fun transfer(slot: Int, index: Int) {
        if (slot >= 0) categories[slot] = index
    }

    fun release(slot: Int) {
        if (slot < 0) return
        categories[slot] = -1
        free[freeCount++] = slot
    }

    fun snapshot(liveAllocations: LongArray): List<HeapAgeSnapshot> {
        val now = MonoClock.nowMs()
        val tracked = LongArray(overflows.size)
        val oldest = LongArray(overflows.size) { -1L }
        val buckets = LongArray(overflows.size * Bucket.entries.size)
        for (slot in categories.indices) {
            val category = categories[slot]
            if (category < 0) continue
            val age = (now - started[slot]).coerceAtLeast(0L)
            tracked[category]++
            oldest[category] = maxOf(oldest[category], age)
            val bucket = when {
                age < 1000L -> Bucket.UNDER_SECOND
                age < AGE_MINUTE_MS -> Bucket.UNDER_MINUTE
                age < AGE_FIVE_MINUTES_MS -> Bucket.UNDER_FIVE_MINUTES
                age < AGE_HOUR_MS -> Bucket.UNDER_HOUR
                else -> Bucket.OLDER
            }
            buckets[category * Bucket.entries.size + bucket.ordinal]++
        }
        return List(overflows.size) { index ->
            val offset = index * Bucket.entries.size
            HeapAgeSnapshot(
                tracked[index], liveAllocations[index] - tracked[index], overflows[index],
                oldest[index].takeIf { it >= 0L },
                buckets[offset + Bucket.UNDER_SECOND.ordinal], buckets[offset + Bucket.UNDER_MINUTE.ordinal],
                buckets[offset + Bucket.UNDER_FIVE_MINUTES.ordinal], buckets[offset + Bucket.UNDER_HOUR.ordinal],
                buckets[offset + Bucket.OLDER.ordinal],
            )
        }
    }
}
