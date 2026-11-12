// NEW: the head's quota tracker (see QuotaTracker): upstream headers observed on a round become
// the unified headers every client response carries, the file survives a restart, and a round
// without either family changes nothing. The extra family is a test-local QuotaHeaderFamily fake
// so this module does not construct :providers-codex (V4-24).
package splice.head.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.usage.QuotaHeaderRead
import splice.core.usage.QuotaJson
import splice.core.usage.QuotaSlots
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.retry.QuotaHeaderFamily
import java.nio.file.Files
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

    // Oct 10, 2026 (Marlin's ruling): Kimi answered `{}`, and a three-week-old reading was still drawn as today's.
    @Test
    fun `a provider's answer that it has no usage replaces the older reading, and still reads so after a restart`() {
        val first = tracker()
        first.record(QuotaSnapshot(sevenDay = QuotaWindow(26.0, null, 604_800L), updatedAt = now - 86_400_000L))
        first.record(QuotaSnapshot(updatedAt = now))
        val gone = first.snapshot()!!
        assertNull(gone.sevenDay, "the older week is no longer drawn")
        assertEquals(now, gone.updatedAt, "the answer keeps its time")
        val after = tracker().snapshot()!!
        assertTrue(after.answeredEmpty, "a restart still reads the provider's answer")
        first.record(QuotaSnapshot(sevenDay = QuotaWindow(4.0, null, 604_800L), updatedAt = now))
        assertEquals(4.0, first.snapshot()!!.sevenDay!!.usedPercent, 1e-9, "a later reading draws again")
    }

    @Test
    fun `a retired account retains late quota evidence without recreating its deleted file`() {
        val quota = tracker()
        val file = dir.resolve("codex-quota.json")
        val first = QuotaSnapshot(fiveHour = QuotaWindow(10.0, 1_788_010_000L, 18_000L))
        quota.record(first)
        quota.retire()
        Files.delete(file)
        val late = QuotaSnapshot(fiveHour = QuotaWindow(20.0, 1_788_020_000L, 18_000L))

        quota.record(late)

        assertEquals(late, quota.snapshot())
        assertFalse(Files.exists(file), "a completed removed-account turn cannot resurrect its folder")
    }

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

    @Test
    fun `credential observations carry the same timestamped windows while the head aggregate stays unchanged`() {
        val tracker = tracker()
        val observed = mutableListOf<Pair<String, QuotaSnapshot>>()
        tracker.credentialListener = CredentialQuotaListener { key, snapshot -> observed += key to snapshot }
        val headers = mapOf(
            "anthropic-ratelimit-unified-5h-utilization" to "0.25",
            "anthropic-ratelimit-unified-7d-utilization" to "0.85",
        )
        tracker.observe("proved-login", QuotaHeaderRead { headers[it] })
        assertEquals(listOf("proved-login"), observed.map { it.first })
        assertEquals(now, observed.single().second.updatedAt)
        assertEquals(tracker.snapshot(), observed.single().second)
        assertEquals(25.0, tracker.snapshot()?.fiveHour?.usedPercent)
        assertEquals(85.0, tracker.snapshot()?.sevenDay?.usedPercent)
        tracker.observe(null, QuotaHeaderRead { headers[it] })
        tracker.observe("other-login", QuotaHeaderRead { null })
        assertEquals(1, observed.size, "no carrier or no windows supplies no login observation")
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

    // V4-327, the observed shape: take-resume-5's first status rows read "5h 0%  7d 52%" from the
    // snapshot an earlier daemon run persisted, while the plan stood at 71% and 98%.
    @Test
    fun `a snapshot the last run persisted hours ago rides no client response`() {
        persist(
            QuotaSnapshot(
                fiveHour = QuotaWindow(95.0, now / 1_000L - 3_600L, 18_000L),
                sevenDay = QuotaWindow(52.0, now / 1_000L + 345_600L, 604_800L),
                updatedAt = now - 8 * 3_600_000L,
            ),
        )
        val t = tracker()
        assertEquals(52.0, t.snapshot()?.sevenDay?.usedPercent, "the file still restores it")
        assertEquals(emptyMap<String, String>(), t.clientHeaders(), "but nothing hours old is stated as now")
        assertEquals(
            setOf("anthropic-ratelimit-unified-status", "anthropic-ratelimit-unified-reset"),
            t.clientHeadersRejected(1_788_030_000L).keys,
            "nor on a refusal",
        )
    }

    @Test
    fun `a reading from a minute ago is stated, less a window whose reset has passed`() {
        persist(
            QuotaSnapshot(
                fiveHour = QuotaWindow(95.0, now / 1_000L - 60L, 18_000L),
                sevenDay = QuotaWindow(98.0, now / 1_000L + 345_600L, 604_800L),
                updatedAt = now - 60_000L,
            ),
        )
        val out = tracker().clientHeaders()
        assertEquals("0.9800", out["anthropic-ratelimit-unified-7d-utilization"])
        assertNull(out["anthropic-ratelimit-unified-5h-utilization"], "the 5h window started over: $out")
    }

    @Test
    fun `a reading stands for fifteen minutes and not a second more`() {
        val week = QuotaWindow(98.0, now / 1_000L + 345_600L, 604_800L)
        persist(QuotaSnapshot(sevenDay = week, updatedAt = now - 900_000L))
        assertEquals("0.9800", tracker().clientHeaders()["anthropic-ratelimit-unified-7d-utilization"])
        persist(QuotaSnapshot(sevenDay = week, updatedAt = now - 901_000L))
        assertEquals(emptyMap<String, String>(), tracker().clientHeaders())
        persist(QuotaSnapshot(sevenDay = week))
        assertEquals(emptyMap<String, String>(), tracker().clientHeaders(), "a reading with no time is not current")
    }

    private fun persist(snapshot: QuotaSnapshot) {
        Files.writeString(dir.resolve("codex-quota.json"), QuotaJson().encode(snapshot))
    }
}
