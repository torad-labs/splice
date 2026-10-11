package splice.upstream.transport

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.upstream.Waiter

class UpstreamClientRetryCurveTest {
    private val slept = mutableListOf<Long>()

    private fun client(curve: BackoffCurve) = UpstreamClient(
        totalTimeoutMs = 900_000L,
        maxRetries = 4,
        pacing = RetryPacing(curve, waiter = Waiter { slept += it }),
    )

    @Test
    fun `a round restart sleeps on the head's configured curve, doubling to its cap`() = runTest {
        val backoff = client(BackoffCurve(baseMs = 40, capMs = 100, jitterPct = 0)).retryBackoff

        listOf(0, 1, 2, 3).forEach { attempt -> backoff(attempt, 0L) }

        assertEquals(listOf(40L, 80L, 100L, 100L), slept)
    }

    // The curve is live: widened while a client exists, it governs the NEXT sleep, and the one already taken is the
    // one it was.
    @Test
    fun `a curve widened after the client was built changes the next sleep and not the one already taken`() = runTest {
        var curve = BackoffCurve(baseMs = 40, capMs = 100, jitterPct = 0)
        val live = RetryPacing(live = LiveRetryCurve { curve }, waiter = Waiter { slept += it })
        val backoff = UpstreamClient(totalTimeoutMs = 900_000L, maxRetries = 4, pacing = live).retryBackoff

        backoff(0, 0L)
        curve = BackoffCurve(baseMs = 300, capMs = 1_000, jitterPct = 0)
        backoff(0, 0L)
        backoff(2, 0L)

        assertEquals(listOf(40L, 300L, 1_000L), slept)
    }

    @Test
    fun `a server minimum longer than the curve wins for a round restart`() = runTest {
        val backoff = client(BackoffCurve(baseMs = 40, capMs = 100, jitterPct = 0)).retryBackoff

        backoff(0, 700L)

        assertEquals(listOf(700L), slept)
    }
}
