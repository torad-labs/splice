// NEW: RequestMaterializationGate contract — with maxConcurrent=1, a second withLease must not
// enter its block until the first has released the permit. Uses UnconfinedTestDispatcher +
// CompletableDeferred (HeadServerCapacityTest/SingleFlightTest convention) so entry order is
// deterministic: the second coroutine's launch runs eagerly up to Semaphore.acquire(), which
// suspends it on the exhausted permit instead of letting the block run. If the semaphore bound
// were removed, "second-enter" would land before "first-exit" and the assertions below would fail.
package head

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.gateway.head.RequestMaterializationGate

class RequestMaterializationGateTest {

    @Test
    fun `second lease cannot enter until the first releases its permit`() = runTest(UnconfinedTestDispatcher()) {
        val gate = RequestMaterializationGate(maxConcurrent = 1)
        val order = mutableListOf<String>()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            gate.withLease {
                order += "first-enter"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-exit"
            }
        }

        firstEntered.await()

        val second = async {
            gate.withLease {
                order += "second-enter"
                secondEntered.complete(Unit)
            }
        }

        // The single permit is held by `first`, so `second`'s block must still be blocked on
        // Semaphore.acquire() — it has not run at all yet.
        assertFalse(secondEntered.isCompleted, "second lease entered before the first released its permit")
        assertEquals(listOf("first-enter"), order)

        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf("first-enter", "first-exit", "second-enter"), order)
    }
}
