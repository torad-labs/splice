// NEW: a changed-cell save does not allocate metadata proportional to unrelated retained records.
package splice.provider.codex.v4340

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.CodeModeRecords
import java.lang.management.ManagementFactory

internal class CodeModeSaveAllocationTest : CodeModeFilesTestSupport() {
    @Test
    fun `changed-cell persistence allocation stays constant as a conversation grows`() {
        val registry = registry()
        val target = registry.script("allocation", 0)
        val counter = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(counter.isThreadAllocatedMemorySupported)
        counter.isThreadAllocatedMemoryEnabled = true
        repeat(20) { registry.complete(target, "warm-$it") }
        val small = measure(counter) { registry.complete(target, "small-$it") }
        (1..1_000).forEach { n ->
            val record = CodeModeRecords.of("allocation", n, futureRecordTs)
            assertTrue(registry.add(record))
            registry.complete(record, "done")
        }
        repeat(20) { registry.complete(target, "warm-$it") }
        val large = measure(counter) { registry.complete(target, "large-$it") }
        println("changed-cell allocated bytes: small=$small large=$large")
        assertTrue(large < small * 2, "single-cell allocated bytes: small=$small large=$large")
    }

    private inline fun measure(counter: ThreadMXBean, action: (Int) -> Unit): Long {
        val thread = Thread.currentThread().threadId()
        val start = counter.getThreadAllocatedBytes(thread)
        repeat(40, action)
        return (counter.getThreadAllocatedBytes(thread) - start) / 40
    }
}
