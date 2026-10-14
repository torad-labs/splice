package splice.core.perf

import splice.core.memory.HeapCategory
import splice.core.memory.HeapLease
import splice.core.memory.HeapReservations
import java.lang.ref.Cleaner

/** Optional byte evidence has a shared ceiling and a charge against the process's existing heap ledger. */
public class InputDigestCheckpointBudget(
    private val heap: HeapReservations,
    private val maximumBytes: Long,
    public val prefixLimitBytes: Int,
) {
    internal data class Allocation(var bytes: Long, var owners: Int = 1)

    private val lock = Any()
    private val cleaner = Cleaner.create()
    private var charged = 0L

    init {
        require(maximumBytes >= 0 && prefixLimitBytes > 0)
    }

    internal fun reserve(bytes: Long): Charge? = synchronized(lock) {
        if (bytes > maximumBytes - charged) return@synchronized null
        val lease = heap.reserve(bytes, HeapCategory.RECENT_HISTORY) ?: return@synchronized null
        charged += bytes
        Charge(lease, Allocation(bytes))
    }

    internal inner class Charge(private val lease: HeapLease, private val allocation: Allocation) : AutoCloseable {
        private var closed = false

        fun retain(owner: ByteArray, retainedBytes: Long): Charge = synchronized(lock) {
            check(!closed && allocation.owners == 1)
            check(retainedBytes in 0..allocation.bytes)
            val retained = Charge(lease.split(retainedBytes), Allocation(retainedBytes))
            allocation.bytes -= retainedBytes
            // The action owns scalar accounting only, never the byte array it watches.
            val _ = cleaner.register(owner, Runnable { retained.close() })
            retained
        }

        fun share(): Charge = synchronized(lock) {
            check(!closed)
            allocation.owners++
            Charge(lease.share(), allocation)
        }

        override fun close() {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    allocation.owners--
                    if (allocation.owners == 0) charged -= allocation.bytes
                    lease.close()
                }
            }
        }
    }
}
