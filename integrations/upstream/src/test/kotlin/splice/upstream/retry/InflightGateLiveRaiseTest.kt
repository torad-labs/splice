// A live raise of maxInflight reaches the requests already parked in the queue at the config-change sample: each of
// them is blocked on its own request and sends nothing new, so no acquire of theirs can notice the raise.
package splice.upstream.retry

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class InflightGateLiveRaiseTest {

    @Test
    fun `a raised limit admits a parked waiter at the config-change sample with no other request`() = runTest {
        var limit = 1
        val gate = InflightGate({ limit })
        val held = gate.admittedSlot()
        val parked = async { gate.admittedSlot() }
        yield()
        assertFalse(parked.isCompleted)
        limit = 2
        gate.sampleObservations()
        val admitted = parked.await()
        assertEquals(2, gate.snapshot().inflight)
        admitted.release()
        held.release()
    }
}
