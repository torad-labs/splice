// NEW: BS-4 DEFECT A walls. The daemon shutdown's head-stop phase must (1) run the N blocking head
// stops CONCURRENTLY (Dispatchers.IO), not serialized on Main's single-thread runBlocking loop, and
// (2) cap the phase at a deadline so one wedged head can't hold shutdown open — with control stopping
// after regardless. Plus Main's halt watchdog: a teardown that overruns the deadline force-terminates
// the JVM (the guarantee SIGTERM lacked), while a clean teardown never halts.
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.app.DaemonProcess
import splice.app.head.HeadShutdown
import splice.core.head.Head
import splice.core.head.HeadHealth
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DaemonStopDeadlineTest {

    private val headLifecycle = HeadShutdown()
    private val process = DaemonProcess()

    // A fake head whose stop() runs [onStop] — a barrier wait models a BLOCKING engine stop (the
    // serialization hazard); awaitCancellation models a cancellable drain that never converges.
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
        // 3 heads, each BLOCKING until all three are inside stop() at once. Concurrent on
        // Dispatchers.IO, all three reach the barrier and pass; serialized on the caller's single
        // runBlocking thread (the pre-fix defect), the first waits alone and the barrier breaks. The
        // concurrency is proven by the rendezvous itself, not by an elapsed-time bound (V4-139).
        val allStopping = CyclicBarrier(3)
        val met = AtomicInteger(0)
        val heads = (1..3).map {
            FakeHead("h$it") {
                allStopping.await(5, TimeUnit.SECONDS)
                met.incrementAndGet()
            }
        }
        var controlStopped = false
        runBlocking { headLifecycle.stopHeads(heads, budgetMs = 10_000, log = {}) { controlStopped = true } }
        assertEquals(3, met.get(), "all three blocking stops were inside stop() at the same time")
        assertTrue(heads.all { it.stopped.get() }, "every head stop ran to completion")
        assertTrue(controlStopped, "control stops after the heads")
    }

    // The @Timeout is a hang backstop, not the assertion: a drain that is awaitCancellation() cannot
    // end by itself, so stopHeads RETURNING at all is the proof the budget cancelled it (V4-139; this
    // was an elapsed-under-3s bound, a wall-clock bet on a loaded box).
    @Test
    @Timeout(30)
    fun `a head whose drain never converges cannot hold stop past the budget, and control still stops`() {
        val slow = FakeHead("slow") { awaitCancellation() }
        var controlStopped = false
        runBlocking { headLifecycle.stopHeads(listOf(slow), budgetMs = 400, log = {}) { controlStopped = true } }
        assertFalse(slow.stopped.get(), "the wedged head's stop was cancelled at the budget, not awaited")
        assertTrue(controlStopped, "control stops even when a head exceeds the budget")
    }

    @Test
    fun `the halt watchdog force-terminates a teardown that overruns the deadline`() {
        val halts = AtomicInteger(0)
        val halted = CountDownLatch(1)
        process.runBoundedTeardown(
            deadlineMs = 150,
            halt = {
                halts.incrementAndGet()
                halted.countDown()
            },
        ) {
            // A teardown that overruns the deadline: it ends only when the watchdog has halted
            // (bounded, so a watchdog that never fires fails here instead of hanging).
            assertTrue(halted.await(10, TimeUnit.SECONDS), "the watchdog never halted an overrunning teardown")
        }
        assertEquals(1, halts.get(), "the watchdog halts exactly once when teardown overruns")
    }

    @Test
    fun `a clean teardown never halts`() {
        val halts = AtomicInteger(0)
        process.runBoundedTeardown(deadlineMs = 200, halt = { halts.incrementAndGet() }) {
            // returns immediately — well under the deadline
        }
        // A PROOF OF ABSENCE, and the one wall-clock wait this file keeps (V4-139): nothing signals
        // that a disarmed watchdog did not fire, so the only instrument is a window past the deadline.
        // Observing the disarm without it needs a scheduler seam in Main.runBoundedTeardown.
        Thread.sleep(500) // wait past the deadline: the disarmed watchdog must not fire
        assertEquals(0, halts.get(), "a clean finish disarms the watchdog")
    }
}
