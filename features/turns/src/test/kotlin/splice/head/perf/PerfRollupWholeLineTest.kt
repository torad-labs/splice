// The hourly rollup counts a turn only once that turn's request-log line is whole on disk, so a stop or a halt that
// cuts the file lane leaves the rollup behind the log, never ahead of it (Oct 11, 3:26 to 3:28 AM CT).
package splice.head.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.PerfSnapshot
import splice.core.util.AsyncFileIo
import splice.core.util.FileIoTask
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PerfRollupWholeLineTest {
    private val snap = PerfSnapshot(emptyMap(), emptyMap())

    private fun wholeLines(file: Path): List<String> {
        if (!Files.exists(file)) return emptyList()
        return Files.readString(file).substringBeforeLast('\n', "").lines().filter { it.isNotEmpty() }
    }

    @Test
    fun `every row the rollup counted is a whole line on disk at the moment it was counted`(@TempDir tmp: Path) {
        val file = tmp.resolve("burst-perf.jsonl")
        val stats = PerfStats(file)
        val counted = AtomicInteger(0)
        val violations = CopyOnWriteArrayList<String>()
        val producers = (0 until 4).map { p ->
            thread {
                repeat(100) { n ->
                    val ts = 1_000_000L + p * 1_000 + n
                    val counting = FileIoTask {
                        val onDisk = wholeLines(file).any { it.contains("\"ts\":$ts,") }
                        if (!onDisk) violations += "row $ts counted before its line was whole"
                        counted.incrementAndGet()
                    }
                    stats.record(PerfRowMeta("m", "ok", false), snap, at = ts, whole = counting)
                }
            }
        }
        producers.forEach { it.join() }
        assertTrue(AsyncFileIo.drain(), "the lane drains")
        assertEquals(emptyList<String>(), violations)
        assertEquals(400, counted.get())
        assertEquals(400, wholeLines(file).size)
    }

    @Test
    fun `a lane cut mid-burst counts nothing the log lacks`(@TempDir tmp: Path) {
        val file = tmp.resolve("halt-perf.jsonl")
        val stats = PerfStats(file)
        val held = CountDownLatch(1)
        val started = CountDownLatch(1)
        // A write stuck on the lane stands in for the halt: whatever queues behind it never runs.
        assertTrue(
            AsyncFileIo.submit {
                started.countDown()
                held.await(30, TimeUnit.SECONDS)
            },
        )
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val counted = AtomicInteger(0)
        repeat(50) { n ->
            val counting = FileIoTask { counted.incrementAndGet() }
            stats.record(PerfRowMeta("m", "ok", false), snap, at = 2_000_000L + n, whole = counting)
        }
        try {
            // The "kill": the process ends here, with the lane still held.
            assertEquals(0, counted.get(), "nothing is counted while its line is not on disk")
            assertEquals(0, wholeLines(file).size)
        } finally {
            held.countDown()
            assertTrue(AsyncFileIo.drain(), "the lane drains before the temp folder is deleted")
        }
        assertEquals(50, counted.get())
        assertEquals(50, wholeLines(file).size)
    }
}
