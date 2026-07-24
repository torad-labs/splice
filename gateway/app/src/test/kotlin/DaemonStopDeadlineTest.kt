// NEW: BS-4 DEFECT A walls. The daemon shutdown's head-stop phase must (1) run the N blocking head
// stops CONCURRENTLY (Dispatchers.IO), not serialized on Main's single-thread runBlocking loop, and
// (2) cap the phase at a deadline so one wedged head can't hold shutdown open — with control stopping
// after regardless. Plus Main's halt watchdog: a teardown that overruns the deadline force-terminates
// the JVM (the guarantee SIGTERM lacked), while a clean teardown never halts.
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.runBoundedTeardown
import splice.app.stopHeads
import splice.core.head.Head
import splice.core.head.HeadHealth
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DaemonStopDeadlineTest {

    // A fake head whose stop() runs [onStop] — Thread.sleep models a BLOCKING engine stop (the
    // serialization hazard); delay models a cancellable drain that never converges.
    private class FakeHead(override val key: String, private val onStop: suspend () -> Unit) : Head {
        override val label = key
        override val port = 0
        val stopped = AtomicBoolean(false)
        override suspend fun start() = Unit
        override suspend fun stop() {
            onStop()
            stopped.set(true)
        }

        override fun healthSnapshot() = HeadHealth(ok = false, running = false, port = 0, version = "test")
    }

    @Test
    fun `blocking head stops run in parallel, not serialized`() {
        // 3 heads each blocking 1s. Concurrent on Dispatchers.IO ~= 1s; serialized on the caller's
        // single runBlocking thread (the pre-fix defect) ~= 3s. A generous budget so none are capped.
        val heads = (1..3).map { FakeHead("h$it") { Thread.sleep(1_000) } }
        var controlStopped = false
        val elapsedMs = measureMs {
            runBlocking { stopHeads(heads, budgetMs = 10_000, log = {}) { controlStopped = true } }
        }
        assertTrue(elapsedMs < 2_500, "parallel head stops finish near one stop (~1s), was ${elapsedMs}ms")
        assertTrue(heads.all { it.stopped.get() }, "every head stop ran to completion")
        assertTrue(controlStopped, "control stops after the heads")
    }

    @Test
    fun `a head whose drain never converges cannot hold stop past the budget, and control still stops`() {
        // One head that would drain far past the budget; withTimeoutOrNull must cap the phase.
        val slow = FakeHead("slow") { delay(60_000) }
        var controlStopped = false
        val elapsedMs = measureMs {
            runBlocking { stopHeads(listOf(slow), budgetMs = 400, log = {}) { controlStopped = true } }
        }
        assertTrue(elapsedMs < 3_000, "the phase is capped near the 400ms budget, was ${elapsedMs}ms")
        assertFalse(slow.stopped.get(), "the wedged head's stop was cancelled at the budget, not awaited")
        assertTrue(controlStopped, "control stops even when a head exceeds the budget")
    }

    @Test
    fun `the halt watchdog force-terminates a teardown that overruns the deadline`() {
        val halts = AtomicInteger(0)
        runBoundedTeardown(deadlineMs = 150, halt = { halts.incrementAndGet() }) {
            Thread.sleep(600) // teardown that overruns the deadline
        }
        assertEquals(1, halts.get(), "the watchdog halts exactly once when teardown overruns")
    }

    @Test
    fun `a clean teardown never halts`() {
        val halts = AtomicInteger(0)
        runBoundedTeardown(deadlineMs = 200, halt = { halts.incrementAndGet() }) {
            // returns immediately — well under the deadline
        }
        Thread.sleep(500) // wait past the deadline: the disarmed watchdog must not fire
        assertEquals(0, halts.get(), "a clean finish disarms the watchdog")
    }

    private inline fun measureMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }
}
