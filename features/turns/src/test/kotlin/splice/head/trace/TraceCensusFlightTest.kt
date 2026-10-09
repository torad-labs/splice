// one trace count at a time: a page that arrives while a count runs answers with that count,
// the same answer, and starts no second one. The injected file-read boundary holds a real regular day file
// before its read. A FIFO is not a day file; a large file races the scheduler.
package splice.head.trace

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.core.storage.DayFiles
import splice.core.storage.FileVisit
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val HEAD = "claudex"

// DR-186's backstop (JsonlSinkTest's idiom): a count that never ends wedges the suite rather than failing, so the
// case is failed by name past this.
private const val HANG_BACKSTOP_S = 60L

// why: how long a thread may take to reach the count once started, under a contended CI worker
private const val REACH_NS = 10_000_000_000L

// why: how long each count may take once the held file read is released; it takes milliseconds
private const val JOIN_MS = 10_000L

// why: enough of a thread's stack to say where it waits
private const val TOP_FRAMES = 12

@Timeout(value = HANG_BACKSTOP_S, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TraceCensusFlightTest {
    private val json = Json { ignoreUnknownKeys = true }

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
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val held = AtomicBoolean(false)
        val fileRead = object : TraceCountFileRead() {
            override fun <T : Any> read(days: DayFiles, file: Path, visit: FileVisit<T>): T? {
                if (held.compareAndSet(false, true)) {
                    entered.countDown()
                    assertTrue(release.await(REACH_NS, TimeUnit.NANOSECONDS), "count was never released")
                }
                return super.read(days, file, visit)
            }
        }
        val census = TraceCensus(json, fileRead = fileRead, heap = splice.head.syntheticHeapBudget())
        val days = DayFiles(dir, HEAD)
        val answers = arrayOfNulls<TraceCensus.Count>(2)
        val first = thread(name = "first page") { answers[0] = census.count(days) }
        try {
            assertTrue(entered.await(REACH_NS, TimeUnit.NANOSECONDS), "first page never entered its file read")
            val second = thread(name = "second page") { answers[1] = census.count(days) }
            await("the second page waiting for the first count", second) {
                parked(second, second.stackTrace)
            }
            release.countDown()
            first.join(JOIN_MS)
            second.join(JOIN_MS)
            assertEquals(TraceCensus.Count(1, 0), answers[0], "the first page's count")
            assertSame(answers[0], answers[1], "the second page started a count of its own")
        } finally {
            release.countDown()
        }
    }
}
