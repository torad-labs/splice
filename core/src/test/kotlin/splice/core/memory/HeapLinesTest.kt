// NEW: bounded framing rejects large source entries before allocating their body text.
package splice.core.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class HeapLinesTest(@param:TempDir private val temp: Path) {
    @Test
    fun `split UTF8 CRLF empty lines and a final fragment keep exact source text`() {
        val long = "é".repeat(6000)
        val file = temp.resolve("lines")
        Files.writeString(file, "first\r\n\n$long\rlast")
        val heap = HeapBudget(heapLimitBytes = Long.MAX_VALUE, budgetBytes = 1024 * 1024)
        HeapLines(file, heap).use { lines ->
            listOf("first", "", long, "last").forEach { expected ->
                lines.next().use { assertEquals(expected, it.text) }
            }
            assertFalse(lines.hasNext())
        }
    }

    @Test
    fun `a line exceeding the available budget is refused without a body sized allocation`() {
        val file = temp.resolve("large")
        Files.writeString(file, "x".repeat(2 * 1024 * 1024))
        val heap = HeapBudget(heapLimitBytes = Long.MAX_VALUE, budgetBytes = 16 * 1024)
        HeapLines(file, heap).use { lines ->
            val available = heap.available.value
            assertThrows(HeapCapacityException::class.java) { lines.next() }
            assertEquals(available, heap.available.value)
        }
    }

    @Test
    fun `a capacity refusal does not silently consume the source entry`() {
        val file = temp.resolve("retry")
        Files.writeString(file, "kept")
        val heap = HeapBudget(heapLimitBytes = Long.MAX_VALUE, budgetBytes = 64 * 1024)
        HeapLines(file, heap).use { lines ->
            val hold = checkNotNull(heap.reserve(heap.available.value))
            assertThrows(HeapCapacityException::class.java) { lines.next() }
            hold.close()
            lines.next().use { assertEquals("kept", it.text) }
        }
    }

    @Test
    fun `a source growing beyond its counted span cannot exceed its reservation`() {
        val heap = HeapBudget(heapLimitBytes = Long.MAX_VALUE, budgetBytes = 16 * 1024)
        val source = ByteArrayInputStream("grown".toByteArray())
        assertThrows(HeapCapacityException::class.java) { HeapText.Reader.read(source, 4L, heap) }
        assertEquals(heap.limitBytes, heap.available.value)
    }
}
