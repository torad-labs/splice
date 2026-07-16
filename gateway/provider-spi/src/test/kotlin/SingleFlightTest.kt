// NEW: SingleFlight — the "N concurrent callers, ONE block run" invariant AND the coroutine-hazard
// fix: a leader cancelled mid-block must NOT broadcast its cancellation to followers on unrelated
// healthy requests; they re-elect a new leader instead. UnconfinedTestDispatcher = eager execution
// so a leader actually SUSPENDS at the gate while followers register (StandardTestDispatcher would
// run each coroutine to completion serially, defeating the coalescing being tested).
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
        val sf = SingleFlight<Int>()
        val runs = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val jobs = (1..5).map {
            async {
                sf.run {
                    runs.incrementAndGet()
                    gate.await() // leader suspends here while the other 4 register as followers
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
    fun `a leader cancelled mid-block does not kill a follower - the follower re-elects`() =
        runTest(UnconfinedTestDispatcher()) {
            val sf = SingleFlight<String>()
            val leaderEntered = CompletableDeferred<Unit>()
            val secondRuns = AtomicInteger(0)

            // Leader: enters the block, signals, then suspends forever until cancelled.
            val leader = launch {
                sf.run {
                    leaderEntered.complete(Unit)
                    CompletableDeferred<String>().await() // never completes; will be cancelled
                    "leader-value"
                }
            }
            leaderEntered.await()

            // Follower: a DIFFERENT, healthy request that coalesced behind the leader.
            val follower = async {
                sf.run {
                    secondRuns.incrementAndGet()
                    "follower-value"
                }
            }

            // Cancel ONLY the leader (its client disconnected). The follower must survive.
            leader.cancel()
            val followerResult = follower.await()

            assertEquals("follower-value", followerResult) // follower re-elected and produced a result
            assertEquals(1, secondRuns.get())
        }

    @Test
    fun `a null leader result is a real value, not a re-elect signal`() = runTest(UnconfinedTestDispatcher()) {
        // SingleFlight<Credentials?> legitimately returns null (no refresh possible). Box<T> keeps
        // "leader returned null" distinct from "leader aborted → re-elect": every follower must get
        // null, and the block must run exactly once (no spurious re-election).
        val sf = SingleFlight<String?>()
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
        assertEquals(1, runs.get()) // exactly one run — null did NOT trigger re-election
        assertTrue(results.all { it == null })
    }

    @Test
    fun `a follower's own cancellation propagates, does not re-elect`() = runTest(UnconfinedTestDispatcher()) {
        val sf = SingleFlight<String>()
        val leaderEntered = CompletableDeferred<Unit>()
        val leaderRelease = CompletableDeferred<String>()
        val leader = launch {
            sf.run {
                leaderEntered.complete(Unit)
                leaderRelease.await() // hold leadership so the follower stays a follower
            }
        }
        leaderEntered.await()
        val follower = launch { sf.run { "never" } } // coalesces behind the leader, then suspends
        follower.cancel() // the FOLLOWER's own client disconnects
        assertTrue(follower.isCancelled)
        leaderRelease.complete("leader-done") // leader finishes cleanly, unaffected
        leader.join()
    }

    @Test
    fun `a genuine block failure propagates to every awaiter`() = runTest(UnconfinedTestDispatcher()) {
        val sf = SingleFlight<Int>()
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
        assertTrue(outcomes.all { it.isFailure }) // all awaiters see the real failure
        assertTrue(outcomes.all { it.exceptionOrNull()?.message == "refresh failed" })
    }
}
