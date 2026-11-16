// NEW: the per-turn cache line in the daemon log (see TurnCacheLine). It says what the turn's row says, and a
// row that billed no input says nothing, so a step that never asked the provider is not logged as a cache miss.
package splice.head.turn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.perf.PerfKeys

class TurnCacheLineTest {
    private val line = TurnCacheLine("claudex")

    @Test
    fun `a turn's cache line carries the tokens its row recorded`() {
        val counters = mapOf(
            PerfKeys.IN_TOKENS to 234_793L,
            PerfKeys.CACHED_TOKENS to 222_976L,
            PerfKeys.OUT_TOKENS to 162L,
        )

        assertEquals(
            "[claudex] cache: input=234793 cached=222976 hit=94% output=162 model=gpt-5.6-terra\n",
            line.rowLine("gpt-5.6-terra", counters, compact = false),
        )
    }

    @Test
    fun `a step that billed no input has no cache line`() {
        val localStep = mapOf(PerfKeys.IN_TOKENS to 0L, PerfKeys.CACHED_TOKENS to 0L, PerfKeys.OUT_TOKENS to 0L)

        assertNull(line.rowLine("gpt-6-luna", localStep, compact = false), "a local step is not a cache miss")
        assertNull(line.rowLine("gpt-6-luna", emptyMap(), compact = false), "nor is a turn told no usage")
    }
}
