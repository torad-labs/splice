// NEW: the head's quota tracker (see QuotaTracker): upstream headers observed on a round become
// the unified headers every client response carries, the file survives a restart, and a round
// without either family changes nothing.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.gateway.usage.HeaderLookup
import splice.gateway.usage.QuotaTracker
import java.nio.file.Path

class QuotaTrackerTest {

    @TempDir
    lateinit var dir: Path

    private val now = 1_788_000_000_000L
    private fun tracker() = QuotaTracker(dir.resolve("codex-quota.json"), WallClock { now }, LogSink { })

    @Test
    fun `x-codex headers on a round become unified headers on the next client response, and survive a restart`() {
        val t = tracker()
        assertNull(t.snapshot())
        assertEquals(emptyMap<String, String>(), t.clientHeaders())
        val codex = mapOf(
            "x-codex-primary-used-percent" to "14",
            "x-codex-primary-window-minutes" to "300",
            "x-codex-primary-reset-at" to "1788010000",
            "x-codex-secondary-used-percent" to "42",
            "x-codex-secondary-window-minutes" to "10080",
            "x-codex-secondary-reset-at" to "1788500000",
        )
        t.observe(HeaderLookup { codex[it] })
        val out = t.clientHeaders()
        assertEquals("0.1400", out["anthropic-ratelimit-unified-5h-utilization"])
        assertEquals("1788010000", out["anthropic-ratelimit-unified-5h-reset"])
        assertEquals("0.4200", out["anthropic-ratelimit-unified-7d-utilization"])
        assertEquals("allowed", out["anthropic-ratelimit-unified-status"])

        t.observe(HeaderLookup { null })
        assertEquals(out, t.clientHeaders(), "a round without either family changes nothing")
        assertEquals(t.snapshot(), tracker().snapshot(), "the file is the restart truth")
    }
}
