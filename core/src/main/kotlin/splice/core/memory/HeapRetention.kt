// One root-owned telemetry accumulator, shared by every descendant read ledger.
package splice.core.memory

/** No payload/lease references, arbitrary labels or per-transition logs. Called under the existing ledger lock. */
internal class HeapRetention {
    private val categories = Array(HeapAccounting.entries.size * HeapCategory.entries.size) { HeapCategoryCounts() }
    private val ages = HeapAges()
    private var chargedBytes = 0L
    private var peakBytes = 0L
    private val observedBytes = HeapByteCount()
    private var peakObservedBytes = 0L

    fun acquire(allocation: HeapAllocation) {
        allocation.ageSlot = ages.acquire(allocation.accounting.index(allocation.category), allocation.startedAtMs)
        record(allocation)
    }

    fun remove(allocation: HeapAllocation) {
        categories[allocation.accounting.index(allocation.category)].adjust(allocation, -1L)
        when (allocation.accounting) {
            HeapAccounting.CHARGED -> chargedBytes -= allocation.bytes
            HeapAccounting.OBSERVED -> observedBytes.adjust(allocation.bytes, -1L)
        }
    }

    fun record(allocation: HeapAllocation) {
        categories[allocation.accounting.index(allocation.category)].adjust(allocation, 1L)
        when (allocation.accounting) {
            HeapAccounting.CHARGED -> {
                chargedBytes += allocation.bytes
                peakBytes = maxOf(peakBytes, chargedBytes)
            }
            HeapAccounting.OBSERVED -> {
                observedBytes.adjust(allocation.bytes, 1L)
                peakObservedBytes = maxOf(peakObservedBytes, observedBytes.value)
            }
        }
    }

    fun transfer(allocation: HeapAllocation, category: HeapCategory) {
        allocation.category = category
        ages.transfer(allocation.ageSlot, allocation.accounting.index(category))
    }

    fun released(allocation: HeapAllocation, reason: HeapRelease) {
        categories[allocation.accounting.index(allocation.category)].released(allocation, reason)
        if (allocation.owners == 0) ages.release(allocation.ageSlot) else record(allocation)
    }

    fun shrunk(allocation: HeapAllocation, bytes: Long) {
        categories[allocation.accounting.index(allocation.category)].shrunk(bytes)
    }

    fun snapshot(limitBytes: Long): HeapRetentionSnapshot {
        val ageRows = ageRows()
        return HeapRetentionSnapshot(
            limitBytes,
            chargedBytes,
            peakBytes,
            HEAP_AGE_CAPACITY,
            HeapCategory.entries.map { category ->
                val index = HeapAccounting.CHARGED.index(category)
                categories[index].snapshot(category, ageRows[index])
            },
        )
    }

    fun observationSnapshot(): HeapObservationSnapshot {
        val ageRows = ageRows()
        return HeapObservationSnapshot(
            "overlapping_owner_estimates_not_process_memory",
            observedBytes.value,
            peakObservedBytes,
            HEAP_AGE_CAPACITY,
            HeapCategory.entries.map { category ->
                val index = HeapAccounting.OBSERVED.index(category)
                categories[index].observationSnapshot(category, ageRows[index])
            },
        )
    }

    private fun ageRows(): List<HeapAgeSnapshot> =
        ages.snapshot(LongArray(categories.size) { categories[it].allocations })
}
