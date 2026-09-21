// NEW: V4-165 — a turn's end is heard on its admission slot, exactly once on every path. The slot
// is released on each exit already (a refusal, an attached drive, a detached compaction drive), so
// the provider's lease on a llama-server slot ends there instead of at each exit separately.
package splice.upstream.local

import admittedSlot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit

class SlotReleaseHookTest {

    // Mutant: release() without drainOnRelease. The lease never ends and its llama-server slot
    // reads as busy forever, so every later conversation is sent unpinned.
    @Test
    fun `a hook runs when the slot is released, and only once`() = runTest {
        val slot = InflightGate(LiveLimit { 1 }).admittedSlot()
        var ended = 0
        slot.onRelease { ended += 1 }

        assertEquals(0, ended)
        slot.release()
        slot.release()
        assertEquals(1, ended)
    }

    // Mutant: onRelease only queues. A hook registered after the release (a turn answered before
    // its registration ran) would never fire.
    @Test
    fun `a hook registered after the release runs at once`() = runTest {
        val slot = InflightGate(LiveLimit { 1 }).admittedSlot()
        slot.release()
        var ended = 0
        slot.onRelease { ended += 1 }

        assertEquals(1, ended)
    }
}
