// PORT-OF: server/src/upstream/gate.mjs contract @ pre-public-port-baseline + the recorded strict improvement:
// FIFO order, 0 = unlimited, hot-resize takes effect for queued waiters, cancel-while-queued
// frees the spot (Node had no such path), release idempotence, snapshot shape.
// G21: the queue itself is boundable via maxQueued (0 = unlimited, default-preserving) —
// overflow answers InflightGate.Admission.AtCapacity synchronously (V4-114: it used to throw
// GatewayAtCapacityException), never silently enqueues.
package splice.upstream.retry

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.head.GatePhase
import splice.core.head.GateSlot

class InflightGateTest {

    @Test
    fun `fifo admission under a limit of one`() = runTest {
        var limit = 1
        val gate = InflightGate({ limit })
        val order = mutableListOf<Int>()
        val first = gate.admittedSlot()
        val a = launch { gate.admittedSlot().also { order.add(2) }.release() }
        val b = launch { gate.admittedSlot().also { order.add(3) }.release() }
        yield()
        order.add(1)
        assertEquals(Triple(1, 2, 1), gate.snapshot().admission())
        first.release()
        a.join()
        b.join()
        assertEquals(listOf(1, 2, 3), order)
        assertEquals(0, gate.snapshot().inflight)
    }

    // DR-147 (provider sweep, 2026-08-31): raising maxInflight live did not drain the waiters
    // already parked. acquire()'s fast path asked only "is there capacity?", and the queue is
    // drained solely by release() — so with every slot held by a long-lived SSE stream there was no
    // release to come, newcomers were admitted straight past waiters queued for the whole backlog,
    // and the operator's relief PATCH did nothing until a stream ended. The file's own header
    // claims FIFO admission and a limit read fresh per admission decision; both were false in this
    // state. Existing arms exercise the live limit and the queue SEPARATELY; the defect is only
    // visible in the mid-state where a waiter is parked AND the limit rises AND a newcomer arrives.
    @Test
    fun `raising the limit admits the parked waiter, not the newcomer - DR-147`() = runTest {
        var limit = 1
        val gate = InflightGate({ limit })
        val holder = gate.admittedSlot() // holds the only slot
        val parked = async { gate.admittedSlot() }
        yield()
        assertEquals(Triple(1, 1, 1), gate.snapshot().admission())

        limit = 2 // the operator's relief PATCH
        val newcomer = async { gate.admittedSlot() }
        yield() // the newcomer runs acquire() and drains
        yield() // the drained waiter's continuation is delivered

        assertTrue(parked.isCompleted, "the raise must admit the waiter that was already queued")
        assertFalse(newcomer.isCompleted, "a newcomer must not overtake a waiter parked before it")
        assertEquals(Triple(2, 1, 2), gate.snapshot().admission())

        holder.release()
        yield()
        parked.await().release()
        newcomer.await().release()
        assertEquals(0, gate.snapshot().inflight)
    }

    @Test
    fun `zero means unlimited`() = runTest {
        val gate = InflightGate({ 0 })
        val slots = (1..20).map { async { gate.admittedSlot() } }.map { it.await() }
        assertEquals(20, gate.snapshot().inflight)
        slots.forEach { it.release() }
    }

    @Test
    fun `queue overflow rejects beyond maxQueued`() = runTest {
        val gate = InflightGate(maxInflight = { 1 }, maxQueued = { 1 })
        val holder = gate.admittedSlot()
        val queued = launch { gate.admittedSlot() }
        yield()
        assertEquals(1, gate.snapshot().queued)

        // V4-114 PIN: the overflow refusal is a VALUE on acquire()'s return type. This line does
        // not compile against the old shape (acquire returned Slot and threw), and an `Acquired`
        // here would fail the assertEquals rather than escaping as an untyped exception.
        assertEquals(InflightGate.Admission.AtCapacity, gate.acquire())
        assertEquals(1, gate.snapshot().queued) // the rejected caller never entered the queue
        assertEquals(1, gate.snapshot().inflight)

        holder.release()
        queued.join() // normal admission still proceeds after the reject
    }

    @Test
    fun `default maxQueued is unlimited`() = runTest {
        val gate = InflightGate({ 1 }) // maxQueued not supplied
        val holder = gate.admittedSlot()
        val waiters = (1..50).map { launch { gate.admittedSlot() } }
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
        val holder = gate.admittedSlot()
        val firstQueued = launch { gate.admittedSlot() }
        yield()
        assertEquals(1, gate.snapshot().queued)

        // rejected under the old limit — V4-114: as a value, not a throw
        assertEquals(InflightGate.Admission.AtCapacity, gate.acquire())

        // operator raises the limit before the next acquire — now admitted into the QUEUE
        // (not necessarily into inflight, which is still bounded at 1)
        queuedLimit = 2
        val secondQueued = launch { gate.admittedSlot() }
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
        val first = gate.admittedSlot()
        var admitted = 0
        val waiters = (1..3).map {
            launch {
                gate.admittedSlot()
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
        val holder = gate.admittedSlot()
        val doomed = launch { gate.admittedSlot() }
        yield()
        assertEquals(1, gate.snapshot().queued)
        doomed.cancelAndJoin()
        assertEquals(0, gate.snapshot().queued)
        holder.release()
        // next acquire proceeds immediately — the cancelled waiter never held the slot
        gate.admittedSlot().release()
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
                val holder = gate.admittedSlot()
                val waiter = launch(kotlinx.coroutines.Dispatchers.Default) { gate.admittedSlot().release() }
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
                kotlinx.coroutines.withTimeout(REACQUIRE_LIVENESS_MS) { gate.admittedSlot().release() }
                val after = gate.snapshot()
                assertEquals(0, after.inflight, "leaked at iteration $it")
                // V4-213: an undelivered permit was never a slot — nothing lingers on the live list,
                // and every slot counted as acquired was counted as released
                assertEquals(emptyList<Any>(), after.live, "a lost hand-off left a live row at iteration $it")
                assertEquals(after.acquired, after.released, "counts disagree at iteration $it: $after")
            }
        }
    }

    @Test
    fun `release is idempotent`() = runTest {
        val gate = InflightGate({ 1 })
        val slot = gate.admittedSlot()
        slot.release()
        slot.release()
        assertEquals(0, gate.snapshot().inflight)
        gate.admittedSlot().release()
    }

    @Test
    fun `slot idle clock ticks and touch resets`() = runTest {
        var now = 1000L
        val gate = InflightGate({ 1 }, clock = { now })
        val slot = gate.admittedSlot()
        now = 1500
        assertEquals(500, slot.idleForMs())
        slot.touch()
        assertEquals(0, slot.idleForMs())
        slot.release()
    }

    // V4-213: the gate reported its counters and live rows as literals. These pin what it measures.

    @Test
    fun `a held slot is one live row, and a released one leaves it and is counted`() = runTest {
        val gate = InflightGate({ 2 })
        val slot = gate.admittedSlot()
        slot.describe("gpt-5.6-sol", compact = false, session = null)
        val held = gate.snapshot()
        assertEquals(listOf("gpt-5.6-sol"), held.live.map { it.label })
        assertEquals(1L to 0L, held.acquired to held.released)
        slot.release()
        slot.release() // idempotent: counted once
        val after = gate.snapshot()
        assertEquals(emptyList<Any>(), after.live)
        assertEquals(1L to 1L, after.acquired to after.released)
    }

    @Test
    fun `a waiter cancelled in the queue never appears and is never counted`() = runTest {
        val gate = InflightGate({ 1 })
        val holder = gate.admittedSlot()
        val doomed = launch { gate.admittedSlot() }
        yield()
        assertEquals(1, gate.snapshot().queued)
        doomed.cancelAndJoin()
        val after = gate.snapshot()
        assertEquals(1, after.live.size, "only the holder is live: $after")
        // the cancelled waiter was neither acquired nor counted as waited
        assertEquals(1L to 0L, after.acquired to after.waited, "$after")
        holder.release()
        assertEquals(1L to 1L, gate.snapshot().let { it.acquired to it.released })
    }

    @Test
    fun `a queued admission is counted as waited with its measured wait`() = runTest {
        var now = 1_000L
        val gate = InflightGate({ 1 }, clock = { now })
        val holder = gate.admittedSlot()
        val quick = async { gate.admittedSlot() }
        yield()
        now = 1_400L
        holder.release()
        val first = quick.await()
        assertEquals(1L to 400L, gate.snapshot().let { it.waited to it.avgWaitMs })
        val slow = async { gate.admittedSlot() }
        yield()
        now = 2_000L // queued at 1400, admitted at 2000: 600, so the mean is 500
        first.release()
        slow.await().release()
        assertEquals(2L to 500L, gate.snapshot().let { it.waited to it.avgWaitMs })
        assertEquals(3L, gate.snapshot().acquired, "the holder never waited, and still counts as acquired")
    }

    @Test
    fun `a slot reads connect until the upstream is heard, then streaming, aged on the gate clock`() = runTest {
        var now = 1_000L
        val gate = InflightGate({ 1 }, clock = { now })
        val slot = gate.admittedSlot()
        now = 1_300L
        val unread = GateSlot("req", compact = false, phase = GatePhase.CONNECT, ageMs = 300, idleMs = 300)
        assertEquals(unread, gate.snapshot().live.single())
        slot.touch()
        slot.describe("gpt-5.6-sol", compact = false, session = null)
        now = 1_500L
        val heard = GateSlot("gpt-5.6-sol", compact = false, phase = GatePhase.STREAMING, ageMs = 500, idleMs = 200)
        assertEquals(heard, gate.snapshot().live.single())
        slot.release()
    }

    @Test
    fun `a live row is led by its session tag, and a compaction keeps its model beside the flag`() = runTest {
        val gate = InflightGate({ 2 })
        val one = gate.admittedSlot()
        val two = gate.admittedSlot()
        one.describe("gpt-6-astra", compact = false, session = "b2e4d8f1")
        two.describe("gpt-6-astra", compact = true, session = "d4c6f9b3")
        val rows = gate.snapshot().live.map { it.label to it.compact }
        assertEquals(listOf("b2e4d8f1 gpt-6-astra" to false, "d4c6f9b3 gpt-6-astra" to true), rows)
        one.release()
        two.release()
    }

    private fun InflightGate.Snapshot.admission() = Triple(inflight, queued, limit)

    private companion object {
        const val RACE_ITERATIONS = 500

        // Generous liveness bound: a leaked permit is PERMANENT, so any finite wait catches it;
        // a tight bound only adds false failures under host load. @Timeout backstops a true hang.
        const val REACQUIRE_LIVENESS_MS = 15_000L
        const val TEST_LIVENESS_S = 60L
    }
}
