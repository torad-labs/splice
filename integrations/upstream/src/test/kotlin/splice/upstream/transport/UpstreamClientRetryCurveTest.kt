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

    @Test
    fun `a server minimum longer than the curve wins for a round restart`() = runTest {
        val backoff = client(BackoffCurve(baseMs = 40, capMs = 100, jitterPct = 0)).retryBackoff

        backoff(0, 700L)

        assertEquals(listOf(700L), slept)
    }
}
