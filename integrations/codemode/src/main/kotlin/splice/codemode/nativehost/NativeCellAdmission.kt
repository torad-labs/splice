package splice.codemode.nativehost

import splice.codemode.host.PoolLimits
import splice.upstream.failure.CodeModeCapacityException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val NATIVE_MIB: Long = 1_024L * 1_024
private const val NATIVE_HEAP_COPY_RESERVE: Long = 2
private const val NATIVE_CELL_RESERVE_MB: Long = 64
private const val NATIVE_HOST_RESERVE_MB: Long = 128
private const val NATIVE_CELLS_PER_WORKER: Int = 4

/** Reservations remain attached to yielded cells and are released only when their execution ends. */
internal class NativeCellAdmission(private val limits: PoolLimits, cellHeapMb: Int) {
    val heapBytes: Long = Math.multiplyExact(cellHeapMb.toLong(), NATIVE_MIB)
    private val cellBytes = Math.addExact(heapBytes * NATIVE_HEAP_COPY_RESERVE, NATIVE_CELL_RESERVE_MB * NATIVE_MIB)
    private val budget = Math.multiplyExact(limits.memoryBudgetMb, NATIVE_MIB)
    private val maxCells = Math.multiplyExact(limits.maxWorkers, NATIVE_CELLS_PER_WORKER)
    private val leases = ConcurrentHashMap.newKeySet<NativeCellLease>()

    init {
        require(cellHeapMb > 0) { "Native cell heap must be positive" }
    }

    @Synchronized
    fun reserve(): NativeCellLease {
        val reserved = (leases.size + 1L) * cellBytes + NATIVE_HOST_RESERVE_MB * NATIVE_MIB
        if (leases.size >= maxCells || reserved > budget) {
            throw CodeModeCapacityException(
                "Native code-mode capacity reached: ${leases.size}/$maxCells live cells; " +
                    "${limits.memoryBudgetMb} MiB memory budget",
            )
        }
        return NativeCellLease(this).also(leases::add)
    }

    fun release(lease: NativeCellLease) {
        leases.remove(lease)
    }

    @Synchronized
    fun closeAll() {
        leases.toList().forEach(NativeCellLease::close)
    }
}

internal class NativeCellLease(private val admission: NativeCellAdmission) : AutoCloseable {
    private val released = AtomicBoolean()

    override fun close() {
        if (released.compareAndSet(false, true)) admission.release(this)
    }
}

internal data class NativePendingStart(
    val host: NativeConnection,
    val sessionId: String,
    val operation: NativeOperation,
)
