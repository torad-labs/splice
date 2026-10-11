// NEW: BS-4 DEFECT A walls. The daemon shutdown's head-stop phase must (1) run the N blocking head
// stops CONCURRENTLY (Dispatchers.IO), not serialized on Main's single-thread runBlocking loop, and
// (2) cap the phase at a deadline so one wedged head can't hold shutdown open — with control stopping
// after regardless. Plus Main's halt watchdog: a teardown that overruns the deadline force-terminates
// the JVM (the guarantee SIGTERM lacked), while a clean teardown never halts.
package splice.app.cli.daemon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.app.DaemonProcess
import splice.app.head.HeadShutdown
import splice.core.head.Head
import splice.core.head.HeadHealth
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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
        // The wedge sits in drain(), which is where a drain that never converges actually lives since the
        // stop became two phases. The budget cancels it; the port is still closed afterwards, because a
        // listener left open by a daemon that is exiting would outlive the process that owns it.
        val slow = DrainingHead("slow") { awaitCancellation() }
        var controlStopped = false
        runBlocking { headLifecycle.stopHeads(listOf(slow), budgetMs = 400, log = {}) { controlStopped = true } }
        assertFalse(slow.drainFinished.get(), "the wedged drain was cancelled at the budget, not awaited")
        assertTrue(slow.stopped.get(), "the port still closes after the drain is cut")
        assertTrue(controlStopped, "control stops even when a head exceeds the budget")
    }

    /** A head that really listens: [onDrain] models its drain, and its stop closes the port. */
    private class DrainingHead(override val key: String, private val onDrain: suspend () -> Unit) : Head {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        override val label = key
        override val port get() = socket.localPort
        val stopped = AtomicBoolean(false)
        val drainFinished = AtomicBoolean(false)
        override suspend fun start() = Unit

        override suspend fun drain() {
            onDrain()
            drainFinished.set(true)
        }

        override suspend fun stop() {
            socket.close()
            stopped.set(true)
        }

        override fun healthSnapshot() = HeadHealth(ok = false, running = false, port = port, version = "test")
    }

    @Test
    @Timeout(60)
    fun `a client connecting in a loop is never refused by an idle head while a busy head drains`() {
        // The defect this pins (measured 2026-10-10 on the operator's box): each head closed its own
        // listener the moment its own drain converged, so an idle head refused every connection for the
        // whole 45s its busy siblings took to drain — ECONNREFUSED to every agent on that port, while
        // the daemon was still running and could have answered. The loop is the check splice-lead asked
        // for: connect over and over across the stop, and count what the kernel said.
        val busyDraining = CountDownLatch(1)
        val letBusyFinish = CountDownLatch(1)
        val idle = DrainingHead("idle") { }
        val busy = DrainingHead("busy") {
            busyDraining.countDown()
            withContext(Dispatchers.IO) { letBusyFinish.await(20, TimeUnit.SECONDS) }
        }
        val refused = AtomicInteger(0)
        val connected = AtomicInteger(0)
        val stopLooping = AtomicBoolean(false)
        val loop = Thread {
            while (!stopLooping.get()) {
                try {
                    Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), idle.port), 1_000) }
                    connected.incrementAndGet()
                } catch (_: IOException) {
                    refused.incrementAndGet()
                }
                Thread.sleep(5)
            }
        }

        loop.start()
        val stop = Thread { runBlocking { headLifecycle.stopHeads(listOf(idle, busy), 30_000, log = {}) {} } }
        stop.start()
        assertTrue(busyDraining.await(10, TimeUnit.SECONDS), "the busy head never reached its drain")
        // While the busy head drains, the idle head has nothing left to do — and must still answer.
        Thread.sleep(300)
        val refusedWhileDraining = refused.get()
        val connectedWhileDraining = connected.get()
        letBusyFinish.countDown()
        stop.join(TimeUnit.SECONDS.toMillis(30))
        stopLooping.set(true)
        loop.join(TimeUnit.SECONDS.toMillis(10))

        assertTrue(connectedWhileDraining > 0, "the loop never reached the idle head at all")
        assertEquals(
            0,
            refusedWhileDraining,
            "an idle head refused $refusedWhileDraining of ${refusedWhileDraining + connectedWhileDraining} " +
                "connections while a sibling drained; every head stays bound until the whole phase ends",
        )
        assertTrue(idle.stopped.get() && busy.stopped.get(), "both ports close once the phase is over")
    }

    @Test
    fun `the halt watchdog force-terminates a teardown that overruns the deadline`() {
        val halts = AtomicInteger(0)
        val halted = CountDownLatch(1)
        runBlocking {
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
        }
        assertEquals(1, halts.get(), "the watchdog halts exactly once when teardown overruns")
    }

    @Test
    fun `a clean teardown never halts`() {
        val halts = AtomicInteger(0)
        runBlocking {
            process.runBoundedTeardown(deadlineMs = 200, halt = { halts.incrementAndGet() }) {
                // returns immediately — well under the deadline
            }
        }
        // A PROOF OF ABSENCE, and the one wall-clock wait this file keeps (V4-139): nothing signals
        // that a disarmed watchdog did not fire, so the only instrument is a window past the deadline.
        // Observing the disarm without it needs a scheduler seam in Main.runBoundedTeardown.
        Thread.sleep(500) // wait past the deadline: the disarmed watchdog must not fire
        assertEquals(0, halts.get(), "a clean finish disarms the watchdog")
    }
}
