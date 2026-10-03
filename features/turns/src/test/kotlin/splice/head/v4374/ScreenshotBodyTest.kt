// NEW: the synthetic sender must supply exactly its declared wire length without whole-body copies.
package splice.head.v4374

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.OutputStream
import java.lang.management.ManagementFactory

class ScreenshotBodyTest {
    @Test
    fun `screenshot streams match their declared length at small and cap sizes`() {
        listOf(1024 * 1024, 2 * 1024 * 1024, 32 * 1024 * 1024).forEach { bytes ->
            val body = ScreenshotBody(bytes)
            body.stream().use {
                assertEquals(bytes.toLong(), it.transferTo(OutputStream.nullOutputStream()), "wire length $bytes")
            }
        }
    }

    @Test
    fun `concurrent senders reuse payload segments rather than allocate bodies`() {
        val allocations = ManagementFactory.getThreadMXBean() as ThreadMXBean
        allocations.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        listOf(1024 * 1024, 2 * 1024 * 1024, 32 * 1024 * 1024).forEach { bytes ->
            val body = ScreenshotBody(bytes)
            body.stream().close()
            val before = allocations.getThreadAllocatedBytes(thread)
            repeat(139) { body.stream().close() }
            val allocated = allocations.getThreadAllocatedBytes(thread) - before
            assertTrue(allocated < 139L * 64 * 1024, "stream construction allocated $allocated bytes for $bytes")
        }
    }
}
