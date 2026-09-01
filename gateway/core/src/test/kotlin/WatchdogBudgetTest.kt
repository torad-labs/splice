// The compact-turn budget (2026-09-01): a compaction's silence before its first output is bounded by
// totalCap alone. Live provenance is in WatchdogBudget.forCompact's KDoc — the first compaction on
// the corrected watchdog tier still died at "no first output within the 300s first-output cap".
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.WatchdogBudget
import kotlin.time.Duration.Companion.seconds

class WatchdogBudgetTest {

    @Test
    fun `forCompact lifts the first-output cap to totalCap and touches nothing else`() {
        val provider = WatchdogBudget(firstByteTimeout = 300.seconds, streamIdle = 180.seconds, totalCap = 900.seconds)
        val compact = provider.forCompact()
        assertEquals(900.seconds, compact.firstByteTimeout, "the only wall before a compaction's first output")
        assertEquals(180.seconds, compact.streamIdle, "mid-output stall detection is unchanged")
        assertEquals(900.seconds, compact.totalCap, "the whole-turn cap is unchanged")
        assertEquals(300.seconds, provider.firstByteTimeout, "the provider budget itself is untouched")
    }
}
