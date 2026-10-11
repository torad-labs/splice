// NEW: Oct 10, 2026 — the poll cadence is a LIVE knob (quotaPollIntervalMs), so what this pins is the one thing a
// snapshotted interval could not do: the length of the NEXT wait, on the cadence already built.
package splice.usage.quota

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.Ticker

class QuotaCadenceTest {
    @Test
    fun `a cadence changed while the daemon runs is the length of the next wait`() = runTest {
        val slept = mutableListOf<Long>()
        var intervalMs = 60_000L
        val cadence = QuotaCadence(intervalMs = { intervalMs }, ticker = Ticker { slept += it; true })
        assertTrue(cadence.awaitNext(failures = 0))

        intervalMs = 300_000L

        assertTrue(cadence.awaitNext(failures = 0))
        assertEquals(listOf(60_000L, 300_000L), slept, "the raise reached the next wait, with nothing rebuilt")
    }

    @Test
    fun `a failure's doubled retry is capped by the cadence as it stands at that wait`() = runTest {
        val slept = mutableListOf<Long>()
        var intervalMs = 300_000L
        val cadence = QuotaCadence(intervalMs = { intervalMs }, ticker = Ticker { slept += it; true })
        // Four failures in a row: 10 s doubled three times is 80 s, well under a five-minute cadence.
        assertTrue(cadence.awaitNext(failures = 4))

        intervalMs = 30_000L

        assertTrue(cadence.awaitNext(failures = 4))
        assertEquals(
            listOf(80_000L, 30_000L),
            slept,
            "the retry ladder and its cap are one reading, so a shortened cadence shortens the ladder",
        )
    }
}
