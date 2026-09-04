// The compact-turn budget (2026-09-01): a compaction's silence before its first output is bounded by
// totalCap alone. Live provenance is in WatchdogBudget.forCompact's KDoc — the first compaction on
// the corrected watchdog tier still died at "no first output within the 300s first-output cap".
// Off, not raised to totalCap: two pollers on one deadline flipped a coin over the verdict (gate run
// 33575037270 on a loaded runner).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.WatchdogBudget
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class WatchdogBudgetTest {

    @Test
    fun `forCompact switches the first-output tier off and touches nothing else`() {
        val provider = WatchdogBudget(firstByteTimeout = 300.seconds, streamIdle = 180.seconds, totalCap = 900.seconds)
        val compact = provider.forCompact()
        assertEquals(
            Duration.INFINITE,
            compact.firstByteTimeout,
            "no pre-output tier: the whole-turn cap is the only wall",
        )
        assertEquals(180.seconds, compact.streamIdle, "mid-output stall detection is unchanged")
        assertEquals(900.seconds, compact.totalCap, "the whole-turn cap is unchanged")
        assertEquals(300.seconds, provider.firstByteTimeout, "the provider budget itself is untouched")
    }
}
