// NEW: trace literal spans keep byte-identical output without span-sized encoder allocations.
package splice.head.trace.body

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.security.DigestOutputStream
import java.security.MessageDigest

class TraceLiteralAllocationTest {
    @Test
    fun `a multi megabyte plain span uses bounded encoding allocation`() {
        val text = "a".repeat(3 * 1024 * 1024)
        val expected = MessageDigest.getInstance("SHA-256").digest(("\"" + text + "\"").toByteArray())
        val allocations = ManagementFactory.getThreadMXBean() as ThreadMXBean
        allocations.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val hash = MessageDigest.getInstance("SHA-256")
        val sink = DigestOutputStream(OutputStream.nullOutputStream(), hash)
        TraceLiteral.encode("warm", OutputStream.nullOutputStream())
        val before = allocations.getThreadAllocatedBytes(thread)
        TraceLiteral.encode(text, sink)
        val allocated = allocations.getThreadAllocatedBytes(thread) - before
        assertArrayEquals(expected, hash.digest())
        assertTrue(allocated < 128 * 1024, "literal span allocated $allocated bytes")
    }

    @Test
    fun `escapes lone surrogates and boundary pairs keep exact encoded bytes`() {
        val text = "x".repeat(4095) + "🧭\"\\\n\r\t\b\u000c\u0000\uD800Ω東京"
        val expected = "\"" + "x".repeat(4095) + "🧭\\\"\\\\\\n\\r\\t\\b\\f\\u0000\\ud800Ω東京\""
        val sink = ByteArrayOutputStream()
        TraceLiteral.encode(text, sink)
        assertArrayEquals(expected.toByteArray(), sink.toByteArray())
    }
}
