// NEW: V4-457 — bounded request decoding and unknown-length reader allocation.
package splice.head

import com.sun.management.ThreadMXBean
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import java.lang.management.ManagementFactory

class RequestBodyReaderTest {
    @Test
    fun `unknown length decoding preserves UTF8 characters split across read boundaries`() = runTest {
        val expected = "x".repeat(16_383) + "café 🐉\n".repeat(4_000)
        val bytes = expected.toByteArray(Charsets.UTF_8)
        val result = RequestBodyReader(1_000).receiveBodyBounded(ByteReadChannel(bytes), null, bytes.size)
        assertEquals(expected, result.text)
        assertEquals(bytes.size, result.bytes)
    }

    @Test
    fun `declared and running byte limits both refuse oversized input`() = runTest {
        val reader = RequestBodyReader(1_000)
        val bytes = "x".repeat(1_001).toByteArray()
        for (declared in listOf(null, bytes.size.toLong())) {
            try {
                reader.receiveBodyBounded(ByteReadChannel(bytes), declared, 1_000)
                error("an oversized body was accepted")
            } catch (tooLarge: RequestBodyTooLarge) {
                assertEquals(1_000, tooLarge.limit)
            }
        }
    }

    @Test
    fun `the read timeout cancels an unfinished channel read`() = runTest {
        val reader = RequestBodyReader(
            1_000,
            RequestBodyRead { _, _ ->
                delay(2_000)
                -1
            },
        )
        var timedOut = false
        try {
            reader.receiveBodyBounded(ByteReadChannel(ByteArray(0)), null, 1_000)
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }
        assertTrue(timedOut)
    }

    @Test
    fun `unknown length 1 point 4 MB input avoids geometric whole body copies`(
        reporter: TestReporter,
    ) = runTest {
        val expected = "x".repeat(1_400_000)
        val bytes = expected.toByteArray(Charsets.UTF_8)
        val reader = RequestBodyReader(1_000)
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: error("JVM allocation counter is required")
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        for ((declared, budget) in listOf(null to 4_800_000, bytes.size.toLong() to 3_200_000)) {
            repeat(10) { reader.receiveBodyBounded(ByteReadChannel(bytes), declared, bytes.size) }
            val channel = ByteReadChannel(bytes)
            val thread = Thread.currentThread().threadId()
            val before = bean.getThreadAllocatedBytes(thread)
            val result = reader.receiveBodyBounded(channel, declared, bytes.size)
            val allocated = bean.getThreadAllocatedBytes(thread) - before
            assertTrue(thread == Thread.currentThread().threadId(), "the measured read must stay on this thread")
            assertEquals(expected, result.text)
            assertEquals(bytes.size, result.bytes)
            reporter.publishEntry("synthetic_reader_declared_$declared", allocated.toString())
            // Unknown input may use segments; a declared length must keep exact preallocation.
            assertTrue(
                allocated < budget,
                "synthetic_reader_allocated_bytes=$allocated; declared=$declared; budget=$budget",
            )
        }
    }
}
