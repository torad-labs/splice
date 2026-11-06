package splice.head.admission

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.observation.PerfAccumulator
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfRecorder
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.retry.GateObservation
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.ObservedInflightGate

class GateObservationTest {
    @Test
    fun `borrowed handles and queued cancellations retain exact permit pressure`() = runBlocking {
        var now = 0L
        val clock = ElapsedClock { now }
        val identity = PerfIdentity("gate", "test", "test", "test")
        val observations = PerfAccumulator(identity)
        val recorder = PerfRecorder(identity, observations, clock, WallClock { now + 60_000L })
        val gate = ObservedInflightGate.create(
            LiveLimit { 1 },
            LiveLimit { 1 },
            GateObservation(recorder, "test"),
            clock,
        )
        val source = (gate.acquire() as InflightGate.Admission.Acquired).slot
        val lease = source.retainSource("session")
        source.release()
        val borrowed = (gate.acquire("session") as InflightGate.Admission.Acquired).slot
        assertEquals(1, gate.snapshot().inflight)
        val queued = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire() }
        assertEquals(1, gate.snapshot().queued)
        now = 250
        assertEquals(InflightGate.Admission.AtCapacity, gate.acquire())
        queued.cancelAndJoin()
        assertEquals(0, gate.snapshot().queued)
        assertEquals(1, gate.snapshot().inflight)
        borrowed.release()
        lease.release()
        assertEquals(0, gate.snapshot().inflight)
        val points = observations.snapshot().points
        assertTrue(points.any { it.metric == "gate_queued_cancelled" && it.last == 1.0 })
        assertTrue(points.any { it.metric == "gate_refused" && it.last == 1.0 })
        assertTrue(points.filter { it.metric == "gate_permits" }.all { (it.max ?: 0.0) <= 1.0 })
    }
}
