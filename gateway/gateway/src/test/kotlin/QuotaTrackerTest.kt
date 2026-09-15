// NEW: the head's quota tracker (see QuotaTracker): upstream headers observed on a round become
// the unified headers every client response carries, the file survives a restart, and a round
// without either family changes nothing. The extra family is a test-local QuotaHeaderFamily fake
// so this module does not construct provider-codex (V4-24).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.usage.QuotaHeaderRead
import splice.core.usage.QuotaSlots
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.gateway.usage.HeaderLookup
import splice.gateway.usage.QuotaTracker
import splice.spi.QuotaHeaderFamily
import java.nio.file.Path

class QuotaTrackerTest {

    @TempDir
    lateinit var dir: Path

    private val now = 1_788_000_000_000L
    private fun tracker() = QuotaTracker(
        dir.resolve("codex-quota.json"),
        WallClock { now },
        LogSink { },
        extraFamily = XCodexFamily(),
    )

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

    @Test
    fun `unified headers on the same round win over the extra family`() {
        val t = tracker()
        val both = mapOf(
            "anthropic-ratelimit-unified-5h-utilization" to "0.10",
            "anthropic-ratelimit-unified-5h-reset" to "1788011111",
            "x-codex-primary-used-percent" to "14",
            "x-codex-primary-window-minutes" to "300",
            "x-codex-primary-reset-at" to "1788010000",
        )
        t.observe(HeaderLookup { both[it] })
        val out = t.clientHeaders()
        assertEquals("0.1000", out["anthropic-ratelimit-unified-5h-utilization"])
        assertEquals("1788011111", out["anthropic-ratelimit-unified-5h-reset"])
    }

    /** x-codex percent / window-minutes / reset-at, slotted by window length. */
    private class XCodexFamily : QuotaHeaderFamily {
        override fun snapshot(header: QuotaHeaderRead, clock: WallClock): QuotaSnapshot? {
            val windows = listOf("primary", "secondary").mapNotNull { which ->
                val used = header("x-codex-$which-used-percent")?.toDoubleOrNull()
                    ?: return@mapNotNull null
                val minutes = header("x-codex-$which-window-minutes")?.toLongOrNull()
                val resetAt = header("x-codex-$which-reset-at")?.toLongOrNull()
                QuotaWindow(used, resetAt, minutes?.let { it * 60L })
            }
            return if (windows.isEmpty()) {
                null
            } else {
                QuotaSlots().snapshot(windows, header("x-codex-plan-type"), clock())
            }
        }
    }
}
