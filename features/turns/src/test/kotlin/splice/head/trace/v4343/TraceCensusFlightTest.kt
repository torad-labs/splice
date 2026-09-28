// NEW: V4-343 — one trace count at a time: a page that arrives while a count runs answers with that count,
// the same answer, and starts no second one. A real, large day file holds the count in flight: day files
// must be regular files, so a FIFO would be rejected before the count ever opened it.
package splice.head.trace.v4343

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.storage.DayFiles
import splice.head.trace.TraceCensus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

private const val HEAD = "claudex"

// DR-186's backstop (JsonlSinkTest's idiom): a count that never ends wedges the suite rather than failing, so the
// case is failed by name past this.
private const val HANG_BACKSTOP_S = 60L

// why: how long a thread may take to reach the count once started, under a contended CI worker
private const val REACH_NS = 10_000_000_000L

// why: long enough that another page can enter while the real-file scan is in progress
private const val LINES = 500_000

// why: how long each count may take once the FIFO has a writer; it takes milliseconds
private const val JOIN_MS = 10_000L

// why: enough of a thread's stack to say where it waits
private const val TOP_FRAMES = 12

@Timeout(value = HANG_BACKSTOP_S, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TraceCensusFlightTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun counting(stack: Array<StackTraceElement>): Boolean =
        stack.any { it.className.startsWith(TraceCensus::class.java.name) }

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
        Files.newBufferedWriter(dir.resolve("$HEAD-2026-09-27.jsonl")).use { writer ->
            repeat(LINES) {
                writer.write(record)
                writer.newLine()
            }
        }
        val census = TraceCensus(json)
        val days = DayFiles(dir, HEAD)
        val answers = arrayOfNulls<TraceCensus.Count>(2)

        val first = thread(name = "first page") { answers[0] = census.count(days) }
        await("a count scanning a regular file", first) { counting(first.stackTrace) }
        val second = thread(name = "second page") { answers[1] = census.count(days) }
        // Joined, it parks on the running count. A second independent count would also return a
        // value, but not the same Count instance as the first.
        await("the second page waiting for the first count", second) {
            parked(second, second.stackTrace)
        }
        first.join(JOIN_MS)
        second.join(JOIN_MS)

        assertEquals(TraceCensus.Count(1, 0), answers[0], "the first page's count")
        assertSame(answers[0], answers[1], "the second page started a count of its own")
    }
}
