// NEW: child work observes a monotonic head fence; a new generation cannot reopen the old one.
package splice.core.turn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeadStopSignalTest {
    @Test
    fun `child work observes its head stop fence before unwinding`() = runBlocking {
        val signal = HeadStopSignal()
        withContext(signal) {
            coroutineScope {
                val read = CompletableDeferred<Unit>()
                val child = async {
                    read.await()
                    currentCoroutineContext()[HeadStopKey]?.isStopping == true
                }
                signal.stop()
                read.complete(Unit)
                assertTrue(child.await())
            }
        }
    }

    @Test
    fun `a fresh head generation cannot reset the old signal`() = runBlocking {
        val old = HeadStopSignal()
        old.stop()
        val next = HeadStopSignal()
        assertTrue(withContext(old) { currentCoroutineContext()[HeadStopKey]?.isStopping == true })
        assertFalse(withContext(next) { currentCoroutineContext()[HeadStopKey]?.isStopping == true })
        assertTrue(old.isStopping)
        assertFalse(next.isStopping)
    }
}
