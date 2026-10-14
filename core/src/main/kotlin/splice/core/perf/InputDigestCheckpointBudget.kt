package splice.core.perf

import splice.core.memory.HeapLease
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations

/** Optional byte evidence has both a local ceiling and a charge against the process's existing heap ledger. */
public class InputDigestCheckpointBudget(
    private val heap: HeapReservations,
    private val maximumBytes: Long,
    public val prefixLimitBytes: Int,
) {
    private val lock = Any()
    private var charged = 0L

    init {
        require(maximumBytes >= 0 && prefixLimitBytes > 0)
    }

    internal fun reserve(bytes: Long): Charge? = synchronized(lock) {
        if (bytes > maximumBytes - charged) return@synchronized null
        val lease = heap.reserve(bytes) ?: return@synchronized null
        charged += bytes
        Charge(lease, bytes)
    }

    internal inner class Charge(private val lease: HeapLease, private var bytes: Long) : AutoCloseable {
        private var closed = false

        fun retain(owner: ByteArray, retainedBytes: Long): Charge = synchronized(lock) {
            check(!closed && retainedBytes in 0..bytes)
            val retained = Charge(lease.split(retainedBytes), retainedBytes)
            bytes -= retainedBytes
            HeapOwners.keep(owner, retained.lease)
            retained
        }

        override fun close() {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    charged -= bytes
                    lease.close()
                }
            }
        }
    }
}
