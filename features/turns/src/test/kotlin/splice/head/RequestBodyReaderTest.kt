// NEW: V4-457 — bounded request decoding and unknown-length reader allocation.
package splice.head

import com.sun.management.ThreadMXBean
import io.ktor.utils.io.ByteReadChannel
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import java.lang.management.ManagementFactory
import java.nio.file.Path

/** Every read below but the cap test expects a body; a refusal there is the failure, named. */
private fun BodyRead.received(): ReceivedBody = when (this) {
    is BodyRead.Received -> body
    is BodyRead.TooLarge -> error("the read refused the body at $limit bytes")
}

class RequestBodyReaderTest {
    @Test
    fun `unknown length decoding preserves UTF8 characters split across read boundaries`() = runTest {
        val expected = "x".repeat(16_383) + "café 🐉\n".repeat(4_000)
        val bytes = expected.toByteArray(Charsets.UTF_8)
        val reader = RequestBodyReader(1_000)
        for (declared in listOf(null, bytes.size.toLong())) {
            val result = reader.receiveBodyBounded(ByteReadChannel(bytes), declared, bytes.size).received()
            assertEquals(expected, result.text)
            assertEquals(bytes.size, result.bytes)
        }
    }

    @Test
    fun `UTF8 replacement and surrogate pairs match the whole body decoder at every boundary`() = runTest {
        val prefix = ByteArray(16_383) { 'x'.code.toByte() }
        val bodies = listOf(
            "🙂".toByteArray(),
            prefix + byteArrayOf(0xE2.toByte(), 0x82.toByte()),
            prefix + byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x99.toByte(), 0x82.toByte(), 0xFF.toByte()),
        )
        val reader = RequestBodyReader(5_000)
        for (bytes in bodies) {
            for (declared in listOf(null, 1L, bytes.size.toLong())) {
                val result = reader.receiveBodyBounded(ByteReadChannel(bytes), declared, bytes.size).received()
                assertEquals(bytes.toString(Charsets.UTF_8), result.text)
                assertEquals(bytes.size, result.bytes)
            }
        }
    }

    @Test
    fun `declared body staging never allocates a body sized contiguous byte array`(@TempDir tmp: Path) = runTest {
        val bytes = ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() }
        val reader = RequestBodyReader(5_000)
        reader.receiveBodyBounded(ByteReadChannel("warmup"), 6, 6)
        val capture = tmp.resolve("body-staging.jfr")
        Recording().use { recording ->
            recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace()
            recording.start()
            val result = reader.receiveBodyBounded(ByteReadChannel(bytes), bytes.size.toLong(), bytes.size).received()
            assertEquals(bytes.size, result.text.length)
            recording.stop()
            recording.dump(capture)
        }
        val events = RecordingFile.readAllEvents(capture)
        assertTrue(events.any { it.getLong("allocationSize") >= bytes.size }, "the recorder must see the final String")
        val stagedArrays = events.filter { event ->
            val frames = event.stackTrace?.frames.orEmpty()
            val reader = frames.any { frame ->
                frame.method.type.name.startsWith("splice.head.RequestBody") &&
                    !frame.method.type.name.startsWith("splice.head.RequestBodyReaderTest")
            }
            val finalString = frames.any { frame ->
                frame.method.type.name == "java.lang.String" && frame.method.name in listOf("<init>", "join")
            }
            event.getLong("allocationSize") >= bytes.size && reader && !finalString
        }
        assertTrue(
            stagedArrays.isEmpty(),
            "raw body staging allocated ${stagedArrays.map { it.getLong("allocationSize") }}",
        )
    }

    @Test
    fun `declared and running byte limits both refuse oversized input`() = runTest {
        val reader = RequestBodyReader(1_000)
        val bytes = "x".repeat(1_001).toByteArray()
        for (declared in listOf(null, bytes.size.toLong())) {
            val read = reader.receiveBodyBounded(ByteReadChannel(bytes), declared, 1_000)
            assertEquals(BodyRead.TooLarge(1_000), read, "declared=$declared")
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
            val result = reader.receiveBodyBounded(channel, declared, bytes.size).received()
            val allocated = bean.getThreadAllocatedBytes(thread) - before
            assertTrue(thread == Thread.currentThread().threadId(), "the measured read must stay on this thread")
            assertEquals(expected, result.text)
            assertEquals(bytes.size, result.bytes)
            reporter.publishEntry("synthetic_reader_declared_$declared", allocated.toString())
            // Segmented staging must retain the declared allocation bound without geometric body copies.
            assertTrue(
                allocated < budget,
                "synthetic_reader_allocated_bytes=$allocated; declared=$declared; budget=$budget",
            )
        }
    }
}
