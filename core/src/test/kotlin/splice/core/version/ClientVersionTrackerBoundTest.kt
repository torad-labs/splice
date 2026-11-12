// NEW: v0.4.0 FEATURES.md §2 — the version tracker forgets the oldest sessions past a fixed
// count, so a long-lived daemon never grows without bound on session ids.
package splice.core.version

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val LIMIT = 4_096

class ClientVersionTrackerBoundTest {
    @Test
    fun `past the limit the oldest session is forgotten, the newest kept`() {
        val tracker = ClientVersionTracker(testedVersion = "2.1.0")
        repeat(LIMIT + 10) { tracker.observe("s-$it", "claude-cli/2.2.0 (external, sdk-cli)") }
        assertEquals(LIMIT, tracker.remembered())
        assertNull(tracker.statuslineWarning("s-0"), "the first session was evicted")
        assertNotNull(tracker.statuslineWarning("s-${LIMIT + 9}"), "the newest still warns once")
        assertNotNull(tracker.aggregateWarning())
    }
}
