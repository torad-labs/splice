// NEW: V4-343 — one trace count at a time: a page that arrives while a count runs answers with that count, the
// same answer, and starts no second one. The first count is held in flight by a day file that is a FIFO: opening
// it waits for a writer, so no count can end until the test opens one.
package splice.head.trace.v4343

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.storage.DayFiles
import splice.head.trace.TraceCensus
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

private const val HEAD = "claudex"

// DR-186's backstop (JsonlSinkTest's idiom): a count that never ends wedges the suite rather than failing, so the
// case is failed by name past this.
private const val HANG_BACKSTOP_S = 60L

// why: how long a thread may take to reach the count once started; it takes milliseconds
private const val REACH_NS = 10_000_000_000L

// why: how long each count may take once the FIFO has a writer; it takes milliseconds
private const val JOIN_MS = 10_000L

// why: enough of a thread's stack to say where it waits
private const val TOP_FRAMES = 12

@Timeout(value = HANG_BACKSTOP_S, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TraceCensusFlightTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun counting(stack: Array<StackTraceElement>): Boolean =
        stack.any { it.className.startsWith(TraceCensus::class.java.name) }

    private fun opening(stack: Array<StackTraceElement>): Boolean =
        stack.any { it.className == "java.nio.channels.FileChannel" && it.methodName == "open" }

    private fun parked(thread: Thread, stack: Array<StackTraceElement>): Boolean =
        thread.state == Thread.State.WAITING &&
            stack.any { it.className == "java.util.concurrent.FutureTask" && it.methodName == "get" }

    /** Waits until [reached] holds, which stays true once it does, failing with [thread]'s stack past the deadline. */
    private fun await(what: String, thread: Thread, reached: () -> Boolean) {
        val deadline = System.nanoTime() + REACH_NS
        while (!reached()) {
            assertTrue(System.nanoTime() < deadline) {
                "$what never happened: ${thread.name} ${thread.state}, at ${thread.stackTrace.take(TOP_FRAMES)}"
            }
            Thread.onSpinWait()
        }
    }

    @Test
    fun `a page that arrives while a count runs answers with it, and no second count starts`(@TempDir dir: Path) {
        val record = """{"kind":"attempt","turn":"t1","ts":1,"attempt":1}"""
        Files.writeString(dir.resolve("$HEAD-2026-09-27.jsonl"), record + "\n")
        val fifo = dir.resolve("$HEAD-2026-09-26.jsonl")
        assumeTrue(ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0, "a FIFO holds the count in flight")
        val census = TraceCensus(json)
        val days = DayFiles(dir, HEAD)
        val answers = arrayOfNulls<TraceCensus.Count>(2)

        val first = thread(name = "first page") { answers[0] = census.count(days) }
        // A count opens files only once it runs, and it waits on the FIFO until the test opens it.
        await("a count opening a file", first) { Thread.getAllStackTraces().values.any { counting(it) && opening(it) } }
        val second = thread(name = "second page") { answers[1] = census.count(days) }
        // Joined, it parks on the running count; counting on its own, it opens files or parks on its own lanes.
        await("the second page inside a count", second) {
            val stack = second.stackTrace
            counting(stack) && (opening(stack) || parked(second, stack))
        }
        RandomAccessFile(fifo.toFile(), "rw").use {
            first.join(JOIN_MS)
            second.join(JOIN_MS)
        }

        assertEquals(TraceCensus.Count(1, 0), answers[0], "the first page's count")
        assertSame(answers[0], answers[1], "the second page started a count of its own")
    }
}
