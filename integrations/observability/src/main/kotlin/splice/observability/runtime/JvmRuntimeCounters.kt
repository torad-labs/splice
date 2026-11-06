package splice.observability.runtime

import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import com.sun.management.UnixOperatingSystemMXBean
import java.lang.management.ManagementFactory

/** Process counters never imply a request's CPU ownership or coroutine queue delay. */
internal class JvmRuntimeCounters {
    private val os = ManagementFactory.getOperatingSystemMXBean()
    private val threads = ManagementFactory.getThreadMXBean()
    private var previousAllocation: Long? = null
    private var previousMs: Long? = null

    fun sample(nowMs: Long, reading: RuntimeReading) {
        val process = os as? OperatingSystemMXBean
        val unix = os as? UnixOperatingSystemMXBean
        reading.add("runtime_cpu_ns", process?.processCpuTime)
        reading.add("runtime_cpu_ratio", process?.processCpuLoad)
        reading.add("runtime_system_load", os.systemLoadAverage)
        reading.add("runtime_threads", threads.threadCount)
        reading.add("runtime_platform_threads", threads.threadCount)
        reading.add("runtime_virtual_threads", null, "ThreadMXBean counts platform threads only")
        reading.add("runtime_fd_count", unix?.openFileDescriptorCount)
        reading.add("runtime_fd_limit", unix?.maxFileDescriptorCount)
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        reading.add(
            "runtime_gc_count",
            collectors.map { it.collectionCount }.takeIf { it.all { count -> count >= 0 } }?.sum(),
        )
        reading.add(
            "runtime_gc_pause_ms",
            collectors.map { it.collectionTime }.takeIf { it.all { time -> time >= 0 } }?.sum(),
        )
        allocation(nowMs, reading)
    }

    private fun allocation(nowMs: Long, reading: RuntimeReading) {
        val bean = threads as? ThreadMXBean
        val allocated = bean?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
            ?.totalThreadAllocatedBytes?.takeIf { it >= 0 }
        reading.add(
            "runtime_allocation_bytes",
            allocated,
            "total allocation counter unavailable or disabled; sampling never enables it",
        )
        reading.add(
            "runtime_allocation_bytes_per_second",
            allocationRate(allocated, nowMs),
            "two supported samples with positive elapsed wall duration required",
        )
        previousAllocation = allocated
        previousMs = nowMs
    }

    private fun allocationRate(allocated: Long?, nowMs: Long): Double? {
        val total = allocated ?: return null
        val previous = previousAllocation?.let { count -> previousMs?.let { time -> count to time } } ?: return null
        val elapsed = nowMs - previous.second
        return if (elapsed > 0 && total >= previous.first) {
            (total - previous.first).toDouble() * MILLIS_PER_SECOND / elapsed
        } else {
            null
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000.0
