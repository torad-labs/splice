// NEW: one root-owned telemetry accumulator, shared by every descendant read ledger.
package splice.core.memory

/** No payload/lease references, arbitrary labels or per-transition logs. Called under the existing ledger lock. */
internal class HeapRetention {
    private val categories = Array(HeapCategory.entries.size) { HeapCategoryCounts() }
    private val ages = HeapAges()
    private var chargedBytes = 0L
    private var peakBytes = 0L

    fun acquire(allocation: HeapAllocation) {
        allocation.ageSlot = ages.acquire(allocation.category, allocation.startedAtMs)
        record(allocation)
    }

    fun remove(allocation: HeapAllocation) {
        categories[allocation.category.ordinal].adjust(allocation, -1L)
        chargedBytes -= allocation.bytes
    }

    fun record(allocation: HeapAllocation) {
        categories[allocation.category.ordinal].adjust(allocation, 1L)
        chargedBytes += allocation.bytes
        peakBytes = maxOf(peakBytes, chargedBytes)
    }

    fun transfer(allocation: HeapAllocation, category: HeapCategory) {
        allocation.category = category
        ages.transfer(allocation.ageSlot, category)
    }

    fun released(allocation: HeapAllocation, reason: HeapRelease) {
        categories[allocation.category.ordinal].released(allocation, reason)
        if (allocation.owners == 0) ages.release(allocation.ageSlot) else record(allocation)
    }

    fun shrunk(allocation: HeapAllocation, bytes: Long) {
        categories[allocation.category.ordinal].shrunk(bytes)
    }

    fun snapshot(limitBytes: Long): HeapRetentionSnapshot {
        val ageRows = ages.snapshot(LongArray(categories.size) { categories[it].allocations })
        return HeapRetentionSnapshot(
            limitBytes,
            chargedBytes,
            peakBytes,
            HEAP_AGE_CAPACITY,
            HeapCategory.entries.map { category ->
                categories[category.ordinal].snapshot(category, ageRows[category.ordinal])
            },
        )
    }
}
