// PORT-OF: server/src/upstream/gate.mjs contract @ 4ca99f7 + the recorded strict improvement:
// FIFO order, 0 = unlimited, hot-resize takes effect for queued waiters, cancel-while-queued
// frees the spot (Node had no such path), release idempotence, snapshot shape.
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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
}
