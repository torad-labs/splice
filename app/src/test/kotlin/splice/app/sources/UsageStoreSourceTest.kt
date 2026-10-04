// NEW: the observation instant reaches the usage view from the stores the head writes. The
// rate-limit file and the quota snapshot both record WHEN they were observed (updated_at, epoch
// millis); UsageStoreSource used to drop both, so /api/usage could not say how old a bar was. The
// view carries them in epoch seconds, the unit the quota windows' resets_at already uses.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.head.usage.QuotaTracker
import splice.head.usage.UsageStore
import java.nio.file.Path

class UsageStoreSourceTest {

    private val now = 1_789_312_411_660L
    private val observedAt = now / 1000

    @Test
    fun `the ratelimit and every quota window carry their observation in epoch seconds`(@TempDir tmp: Path) {
        val store = UsageStore(tmp.resolve("codex-usage.json"), tmp.resolve("codex-ratelimit.json"), clock = { now })
        store.persistRateLimit { name -> mapOf("x-ratelimit-limit-tokens" to "5000")[name] }
        val tracker = QuotaTracker(tmp.resolve("codex-quota.json"), clock = { now })
        tracker.record(
            QuotaSnapshot(QuotaWindow(40.0, observedAt + 3_600, 18_000), QuotaWindow(9.0, null, 604_800), "pro", now),
        )

        val view = UsageStoreSource(store, tracker).snapshot()

        assertEquals(observedAt, view.ratelimit?.observedAt, "the round the headers came from")
        assertEquals(observedAt, view.quota?.fiveHour?.observedAt)
        assertEquals(observedAt, view.quota?.sevenDay?.observedAt, "one snapshot, one observation")
        assertEquals(observedAt + 3_600, view.quota?.fiveHour?.resetsAt, "the sibling unit, unchanged")
    }

    @Test
    fun `a snapshot that names no observation reads null, never 1970`(@TempDir tmp: Path) {
        val store = UsageStore(tmp.resolve("codex-usage.json"), tmp.resolve("codex-ratelimit.json"), clock = { now })
        val tracker = QuotaTracker(tmp.resolve("codex-quota.json"), clock = { now })
        tracker.record(QuotaSnapshot(QuotaWindow(40.0, null, 18_000)))

        val view = UsageStoreSource(store, tracker).snapshot()

        assertNull(view.ratelimit, "no rate-limit read at all")
        assertNull(view.quota?.fiveHour?.observedAt, "updatedAt 0 is the legacy decode, not an instant")
    }
}
