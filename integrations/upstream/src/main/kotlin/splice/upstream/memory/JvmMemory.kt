// NEW: JVM observation work runs only when an authenticated diagnostic caller requests a snapshot.
package splice.upstream.memory

import splice.core.memory.HeapRetentionSnapshot
import java.lang.management.ManagementFactory
import java.lang.management.MemoryUsage
import javax.management.JMException
import javax.management.ObjectName

internal object JvmMemory {
    private val memory = ManagementFactory.getMemoryMXBean()

    fun snapshot(retention: HeapRetentionSnapshot): JvmMemorySnapshot = JvmMemorySnapshot(
        retention,
        region(memory.heapMemoryUsage),
        region(memory.nonHeapMemoryUsage),
        buffer("direct"),
        buffer("mapped"),
        JvmResident.bytes(),
        "unavailable",
    )

    private fun region(usage: MemoryUsage): JvmMemoryRegion = JvmMemoryRegion(
        usage.used,
        usage.committed,
        usage.max.takeIf { it >= 0L },
    )

    /** Fixed BufferPool MXBean names/attributes avoid runtime class-token discovery. */
    private fun buffer(name: String): JvmBufferSnapshot? = try {
        val server = ManagementFactory.getPlatformMBeanServer()
        val pool = ObjectName("java.nio:type=BufferPool,name=$name")
        val count = server.getAttribute(pool, "Count") as? Long
        val capacity = server.getAttribute(pool, "TotalCapacity") as? Long
        val used = server.getAttribute(pool, "MemoryUsed") as? Long
        if (count == null || capacity == null) null else JvmBufferSnapshot(count, used?.takeIf { it >= 0L }, capacity)
    } catch (_: JMException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
