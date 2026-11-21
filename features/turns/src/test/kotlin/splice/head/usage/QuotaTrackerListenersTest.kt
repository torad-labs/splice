// NEW: gateway-no-heads. One quota tracker can feed more than one route (a legacy head and the gateway's runtime for the
// same provider share it), and each wakes its own held turns when a reading turns allowed, so a second registration
// must never replace the first.
package splice.head.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class QuotaTrackerListenersTest {
    @Test
    fun `every route that registered hears a reading turn allowed, not only the last`(@TempDir tmp: Path) {
        val tracker = QuotaTracker(tmp.resolve("quota.json"))
        val first = AtomicInteger()
        val second = AtomicInteger()
        tracker.whenAllowed { first.incrementAndGet() }
        tracker.whenAllowed { second.incrementAndGet() }
        val observedAt = System.currentTimeMillis()
        val resetsAt = observedAt / 1_000L + 3_600L

        // A reading is current only with the time it was taken (QuotaFreshness): one without it never turns "allowed".
        tracker.record(QuotaSnapshot(fiveHour = QuotaWindow(10.0, resetsAt, 18_000L), updatedAt = observedAt))

        assertEquals(1, first.get(), "the route that registered first still hears it")
        assertEquals(1, second.get())
    }
}
