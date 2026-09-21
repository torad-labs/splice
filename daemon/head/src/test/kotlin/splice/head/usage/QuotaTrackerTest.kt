// NEW: the head's quota tracker (see QuotaTracker): upstream headers observed on a round become
// the unified headers every client response carries, the file survives a restart, and a round
// without either family changes nothing. The extra family is a test-local QuotaHeaderFamily fake
// so this module does not construct :providers-codex (V4-24).
package splice.head.usage

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
import splice.upstream.retry.QuotaHeaderFamily
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
        t.observe(QuotaHeaderRead { codex[it] })
        val out = t.clientHeaders()
        assertEquals("0.1400", out["anthropic-ratelimit-unified-5h-utilization"])
        assertEquals("1788010000", out["anthropic-ratelimit-unified-5h-reset"])
        assertEquals("0.4200", out["anthropic-ratelimit-unified-7d-utilization"])
        assertEquals("allowed", out["anthropic-ratelimit-unified-status"])

        t.observe(QuotaHeaderRead { null })
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
        t.observe(QuotaHeaderRead { both[it] })
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

    // V4-51: the refusal variant that V4-50's admission-side 429 consumes.
    @Test
    fun `a rejected refusal states rejected and the plain reset, snapshot or no snapshot`() {
        val t = tracker()
        // With NO tracked snapshot: the refusal is still stated, because on a refusal the deadline
        // IS the message and the window members are optional.
        val blind = t.clientHeadersRejected(1_788_030_000L)
        assertEquals("rejected", blind["anthropic-ratelimit-unified-status"])
        assertEquals("1788030000", blind["anthropic-ratelimit-unified-reset"])
        assertEquals(
            emptyMap<String, String>(),
            t.clientHeaders(),
            "the ALLOWED variant is untouched by the refused one",
        )

        val codex = mapOf(
            "x-codex-primary-used-percent" to "14",
            "x-codex-primary-window-minutes" to "300",
            "x-codex-primary-reset-at" to "1788010000",
            "x-codex-secondary-used-percent" to "42",
            "x-codex-secondary-window-minutes" to "10080",
            "x-codex-secondary-reset-at" to "1788500000",
        )
        t.observe(QuotaHeaderRead { codex[it] })
        val withWindows = t.clientHeadersRejected(1_788_030_000L)
        assertEquals("rejected", withWindows["anthropic-ratelimit-unified-status"])
        assertEquals("1788030000", withWindows["anthropic-ratelimit-unified-reset"])
        assertEquals(
            "0.1400",
            withWindows["anthropic-ratelimit-unified-5h-utilization"],
            "the windows ride along: the deadline says WHEN, the window says WHY",
        )
        assertEquals(
            "allowed",
            t.clientHeaders()["anthropic-ratelimit-unified-status"],
            "and the allowed path is unmoved by a refusal having been built",
        )
    }
}
