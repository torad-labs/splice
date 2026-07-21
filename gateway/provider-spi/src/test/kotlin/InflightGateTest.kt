// PORT-OF: server/src/upstream/gate.mjs contract @ pre-public-port-baseline + the recorded strict improvement:
// FIFO order, 0 = unlimited, hot-resize takes effect for queued waiters, cancel-while-queued
// frees the spot (Node had no such path), release idempotence, snapshot shape.
// G21: the queue itself is boundable via maxQueued (0 = unlimited, default-preserving) —
// overflow rejects synchronously with GatewayAtCapacityException, never silently enqueues.
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import splice.spi.GatewayAtCapacityException
import splice.spi.InflightGate

class InflightGateTest {

    @Test
    fun `fifo admission under a limit of one`() = runTest {
        var limit = 1
        val gate = InflightGate({ limit })
        val order = mutableListOf<Int>()
        val first = gate.acquire()
        val a = launch { gate.acquire().also { order.add(2) }.release() }
        val b = launch { gate.acquire().also { order.add(3) }.release() }
        yield()
        order.add(1)
        assertEquals(InflightGate.Snapshot(1, 2, 1), gate.snapshot())
        first.release()
        a.join()
        b.join()
        assertEquals(listOf(1, 2, 3), order)
        assertEquals(0, gate.snapshot().inflight)
    }

    @Test
    fun `zero means unlimited`() = runTest {
        val gate = InflightGate({ 0 })
        val slots = (1..20).map { async { gate.acquire() } }.map { it.await() }
        assertEquals(20, gate.snapshot().inflight)
        slots.forEach { it.release() }
    }

    @Test
    fun `queue overflow rejects beyond maxQueued`() = runTest {
        val gate = InflightGate(maxInflight = { 1 }, maxQueued = { 1 })
        val holder = gate.acquire()
        val queued = launch { gate.acquire() }
        yield()
        assertEquals(1, gate.snapshot().queued)

        val thrown = assertThrows<GatewayAtCapacityException> { gate.acquire() }
        assertEquals("gateway at capacity", thrown.message)
        assertEquals(1, gate.snapshot().queued) // the rejected caller never entered the queue
        assertEquals(1, gate.snapshot().inflight)

        holder.release()
        queued.join() // normal admission still proceeds after the reject
    }

    @Test
    fun `default maxQueued is unlimited`() = runTest {
        val gate = InflightGate({ 1 }) // maxQueued not supplied
        val holder = gate.acquire()
        val waiters = (1..50).map { launch { gate.acquire() } }
        yield()
        assertEquals(50, gate.snapshot().queued)
        waiters.forEach { it.cancel() }
        waiters.forEach { it.join() }
        holder.release()
    }

    @Test
    fun `hot-resize applies to maxQueued too`() = runTest {
        var queuedLimit = 1
        val gate = InflightGate(maxInflight = { 1 }, maxQueued = { queuedLimit })
        val holder = gate.acquire()
        val firstQueued = launch { gate.acquire() }
        yield()
        assertEquals(1, gate.snapshot().queued)

        // rejected under the old limit
        assertThrows<GatewayAtCapacityException> { gate.acquire() }

        // operator raises the limit before the next acquire — now admitted into the QUEUE
        // (not necessarily into inflight, which is still bounded at 1)
        queuedLimit = 2
        val secondQueued = launch { gate.acquire() }
        yield()
        assertEquals(2, gate.snapshot().queued)

        holder.release() // drains ONE waiter (FIFO: firstQueued) under the still-1 inflight limit
        firstQueued.join()
        secondQueued.cancelAndJoin()
    }

    @Test
    fun `hot resize admits queued waiters on next release`() = runTest {
        var limit = 1
        val gate = InflightGate({ limit })
        val first = gate.acquire()
        var admitted = 0
        val waiters = (1..3).map {
            launch {
                gate.acquire()
                admitted++
            }
        }
        yield()
        assertEquals(0, admitted)
        limit = 4
        first.release() // drain runs under the NEW limit: all three admitted
        waiters.forEach { it.join() }
        assertEquals(3, admitted)
    }

    @Test
    fun `cancel while queued frees the spot`() = runTest {
        val gate = InflightGate({ 1 })
        val holder = gate.acquire()
        val doomed = launch { gate.acquire() }
        yield()
        assertEquals(1, gate.snapshot().queued)
        doomed.cancelAndJoin()
        assertEquals(0, gate.snapshot().queued)
        holder.release()
        // next acquire proceeds immediately — the cancelled waiter never held the slot
        gate.acquire().release()
    }

    @Test
    @Timeout(TEST_LIVENESS_S) // hard backstop: a genuine leak hangs, and must FAIL the suite, never wedge it
    fun `cancel racing admission never leaks the permit`() {
        // Real threads on purpose: the leak window is BETWEEN admission (under the gate lock)
        // and resume delivery (outside it) — unreachable from runTest's single thread. Old code:
        // a waiter cancelled in that window kept the inflight increment forever, so the follow-up
        // acquire below hung once any iteration lost the race. tryResume hands the permit back.
        kotlinx.coroutines.runBlocking {
            repeat(RACE_ITERATIONS) {
                val gate = InflightGate({ 1 })
                val holder = gate.acquire()
                val waiter = launch(kotlinx.coroutines.Dispatchers.Default) { gate.acquire().release() }
                // let the waiter reach the queue
                while (gate.snapshot().queued == 0 && waiter.isActive) yield()
                val releaser = launch(kotlinx.coroutines.Dispatchers.Default) { holder.release() }
                val canceller = launch(kotlinx.coroutines.Dispatchers.Default) { waiter.cancel() }
                releaser.join()
                canceller.join()
                waiter.join()
                // whatever the race outcome, exactly zero or one delivery happened and the permit
                // must be re-acquirable — a real (permanent) leak hangs here forever. The bound is
                // generous on purpose: a leaked permit is PERMANENT, so any finite wait catches it,
                // whereas a tight bound produced false failures under host/CI load (scheduler
                // starvation of the racing Default-dispatcher coroutines), NOT leaks.
                kotlinx.coroutines.withTimeout(REACQUIRE_LIVENESS_MS) { gate.acquire().release() }
                assertEquals(0, gate.snapshot().inflight, "leaked at iteration $it")
            }
        }
    }

    @Test
    fun `release is idempotent`() = runTest {
        val gate = InflightGate({ 1 })
        val slot = gate.acquire()
        slot.release()
        slot.release()
        assertEquals(0, gate.snapshot().inflight)
        gate.acquire().release()
    }

    @Test
    fun `slot idle clock ticks and touch resets`() = runTest {
        var now = 1000L
        val gate = InflightGate({ 1 }, clock = { now })
        val slot = gate.acquire()
        now = 1500
        assertEquals(500, slot.idleForMs())
        slot.touch()
        assertEquals(0, slot.idleForMs())
        slot.release()
    }

    private companion object {
        const val RACE_ITERATIONS = 500

        // Generous liveness bound: a leaked permit is PERMANENT, so any finite wait catches it;
        // a tight bound only adds false failures under host load. @Timeout backstops a true hang.
        const val REACQUIRE_LIVENESS_MS = 15_000L
        const val TEST_LIVENESS_S = 60L
    }
}
