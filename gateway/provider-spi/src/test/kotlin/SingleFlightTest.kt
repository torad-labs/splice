// NEW: SingleFlight — exactly-one-block-per-wave, decoupled from any caller's lifecycle. The block
// runs in an injected scope, so a caller cancelled mid-wait cancels only ITS await, never the shared
// refresh; and N followers coalesce onto ONE run (the re-election design regressed this to one run
// per follower). UnconfinedTestDispatcher = eager execution so callers actually coalesce before the
// gate opens.
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.SingleFlight
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {

    @Test
    fun `N concurrent callers run the block once and all get the result`() = runTest(UnconfinedTestDispatcher()) {
        val sf = SingleFlight<Int>(UnconfinedTestDispatcher(testScheduler))
        val runs = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val jobs = (1..5).map {
            async {
                sf.run {
                    runs.incrementAndGet()
                    gate.await()
                    42
                }
            }
        }
        gate.complete(Unit)
        val results = jobs.map { it.await() }
        assertEquals(1, runs.get()) // exactly one refresh
        assertTrue(results.all { it == 42 })
    }

    @Test
    fun `a cancelled caller does not kill peers - the shared block runs once and completes`() =
        runTest(UnconfinedTestDispatcher()) {
            val sf = SingleFlight<String>(UnconfinedTestDispatcher(testScheduler))
            val runs = AtomicInteger(0)
            val gate = CompletableDeferred<Unit>()

            // Leader initiates the shared refresh, then suspends on the gate.
            val leader = launch {
                sf.run {
                    runs.incrementAndGet()
                    gate.await()
                    "value"
                }
            }
            // TWO followers coalesce behind it — the exact case that regressed to a per-follower run.
            val f1 = async {
                sf.run {
                    runs.incrementAndGet()
                    gate.await()
                    "value"
                }
            }
            val f2 = async {
                sf.run {
                    runs.incrementAndGet()
                    gate.await()
                    "value"
                }
            }

            leader.cancel() // the leader's client disconnects; the shared refresh must continue
            gate.complete(Unit) // the single shared block finishes
            assertEquals("value", f1.await())
            assertEquals("value", f2.await())
            assertEquals(1, runs.get()) // EXACTLY ONE execution despite cancel + 2 followers
        }

    @Test
    fun `a null result is a real value shared by all callers`() = runTest(UnconfinedTestDispatcher()) {
        val sf = SingleFlight<String?>(UnconfinedTestDispatcher(testScheduler))
        val runs = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val jobs = (1..4).map {
            async {
                sf.run {
                    runs.incrementAndGet()
                    gate.await()
                    null
                }
            }
        }
        gate.complete(Unit)
        val results = jobs.map { it.await() }
        assertEquals(1, runs.get())
        assertTrue(results.all { it == null })
    }

    @Test
    fun `a genuine block failure propagates to every awaiter`() = runTest(UnconfinedTestDispatcher()) {
        val sf = SingleFlight<Int>(UnconfinedTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val jobs = (1..3).map {
            async {
                runCatching {
                    sf.run {
                        gate.await()
                        error("refresh failed")
                    }
                }
            }
        }
        gate.complete(Unit)
        val outcomes = jobs.map { it.await() }
        assertTrue(outcomes.all { it.isFailure })
        assertTrue(outcomes.all { it.exceptionOrNull()?.message == "refresh failed" })
    }

    @Test
    fun `a Job-carrying context does not defeat SupervisorJob isolation`() = runTest(UnconfinedTestDispatcher()) {
        // Constructor contract: even if the injected context carries its own Job, the internal
        // SupervisorJob must win so one wave's failure can't poison the instance for the next wave.
        val sf = SingleFlight<Int>(UnconfinedTestDispatcher(testScheduler) + Job())
        val boom = runCatching { sf.run { error("wave1 boom") } }
        assertTrue(boom.isFailure)
        assertEquals(7, sf.run { 7 }) // a fresh wave still works — the instance is not poisoned
    }

    @Test
    fun `a settled refresh is not reused - the next wave starts fresh`() = runTest(UnconfinedTestDispatcher()) {
        val sf = SingleFlight<Int>(UnconfinedTestDispatcher(testScheduler))
        val runs = AtomicInteger(0)
        assertEquals(1, sf.run { runs.incrementAndGet() }) // wave 1
        assertEquals(2, sf.run { runs.incrementAndGet() }) // wave 2 — fresh, not the cached 1
        assertEquals(2, runs.get())
    }

    @Test
    fun `close cancels an in-flight refresh so a lifecycle owner can stop it`() = runTest(UnconfinedTestDispatcher()) {
        // Distinct from a single caller's await cancellation: a LIFECYCLE owner (daemon stop) must
        // be able to cancel the shared refresh so it cannot complete/persist after shutdown.
        val sf = SingleFlight<Int>(UnconfinedTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>()
        var blockCancelled = false
        // A CancellationException in a child launch is that child's own cancellation and does not
        // fail the parent, so join() returns normally once close() cancels the shared refresh.
        val waiter = launch {
            sf.run {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    blockCancelled = true
                    throw e
                }
            }
        }
        started.await()
        sf.close()
        waiter.join()
        assertTrue(blockCancelled, "close() must cancel the in-flight refresh block")
    }
}
