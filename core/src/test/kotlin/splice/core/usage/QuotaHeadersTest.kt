// NEW: quota windows on the wire, both directions (see QuotaHeaders). The client side is pinned to
// what Claude Code reads: utilization as a 0..1 fraction, reset as epoch seconds, plus the status
// header. The upstream side covers Anthropic's unified family; vendor families live on QuotaHeaderFamily.
package splice.core.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.util.WallClock

class QuotaHeadersTest {

    private val now = 1_788_000_000_000L
    private val headers = QuotaHeaders(WallClock { now })

    private fun read(map: Map<String, String>) = QuotaHeaderRead { map[it] }

    @Test
    fun `the client sees Anthropic's own header family, fraction and epoch seconds`() {
        val snapshot = QuotaSnapshot(
            fiveHour = QuotaWindow(14.0, 1_788_010_000L, 18_000L),
            sevenDay = QuotaWindow(42.5, null, 604_800L),
        )
        val out = headers.forClient(snapshot)
        assertEquals("0.1400", out["anthropic-ratelimit-unified-5h-utilization"])
        assertEquals("1788010000", out["anthropic-ratelimit-unified-5h-reset"])
        assertEquals("0.4250", out["anthropic-ratelimit-unified-7d-utilization"])
        assertEquals(
            (now / 1000 + 604_800L).toString(),
            out["anthropic-ratelimit-unified-7d-reset"],
            "no reset: end of the window from now",
        )
        assertEquals("allowed", out["anthropic-ratelimit-unified-status"])
        assertEquals(emptyMap<String, String>(), headers.forClient(QuotaSnapshot()), "an empty snapshot sends nothing")
    }

    @Test
    fun `the unified family from a passthrough upstream round-trips through the snapshot`() {
        val upstream = mapOf(
            "anthropic-ratelimit-unified-5h-utilization" to "0.14",
            "anthropic-ratelimit-unified-5h-reset" to "1788010000",
            "anthropic-ratelimit-unified-7d-utilization" to "0.42",
            "anthropic-ratelimit-unified-7d-reset" to "1788500000",
        )
        val snapshot = headers.fromUpstream(read(upstream))!!
        assertEquals(14.0, snapshot.fiveHour!!.usedPercent, 1e-9)
        assertEquals(1_788_010_000L, snapshot.fiveHour.resetsAt)
        assertEquals(42.0, snapshot.sevenDay!!.usedPercent, 1e-9)
        assertEquals(now, snapshot.updatedAt)
        val relayed = headers.forClient(snapshot)
        assertEquals("0.1400", relayed["anthropic-ratelimit-unified-5h-utilization"])
        assertEquals("1788010000", relayed["anthropic-ratelimit-unified-5h-reset"])
        assertEquals("0.4200", relayed["anthropic-ratelimit-unified-7d-utilization"])
        assertEquals("1788500000", relayed["anthropic-ratelimit-unified-7d-reset"])
    }

    @Test
    fun `fromUpstream ignores the x-codex family`() {
        val plus = mapOf(
            "x-codex-primary-used-percent" to "31.5",
            "x-codex-primary-window-minutes" to "300",
        )
        assertNull(headers.fromUpstream(read(plus)))
        assertNull(headers.fromUpstream(read(mapOf("x-ratelimit-limit-tokens" to "1000"))))
    }

    @Test
    fun `the on-disk codec round-trips and treats junk as no snapshot`() {
        val codec = QuotaJson()
        val snapshot = QuotaSnapshot(QuotaWindow(14.0, 1L, 18_000L), null, "pro", now)
        assertEquals(snapshot, codec.decode(codec.encode(snapshot)))
        assertNull(codec.decode("{not json"))
        assertNull(codec.decode("{}"), "no windows is no snapshot")
    }

    // /api/usage `observed_at`: the recorded observation in the unit `resets_at` uses, and no
    // observation where the source names none — 0 is what a pre-`updated_at` file decodes to.
    @Test
    fun `the observation reads in epoch seconds, and a missing one is null, never 1970`() {
        val observed = QuotaSnapshot(QuotaWindow(14.0, 1L, 18_000L), null, "pro", now + 999)
        assertEquals(now / 1000, observed.observedAtEpochSeconds, "millis truncated to the reset's unit")
        val legacy = QuotaJson().decode("""{"five_hour":{"used_percent":14.0}}""")!!
        assertNull(legacy.observedAtEpochSeconds, "a file written before updated_at")
        assertEquals(now / 1000, RateLimitState(1000, 100, "6m0s", now).observedAtEpochSeconds)
        assertNull(RateLimitState(1000, 100, "6m0s").observedAtEpochSeconds, "a ratelimit file without updated_at")
    }

    // ---- V4-51: the refusal variant, and the byte-identity that makes adding it safe -------------

    @Test
    fun `BYTE IDENTITY - the default call writes today's family and not one member more`() {
        // The whole row rests on this. A response that passes no status must not move a single byte,
        // so the key ORDER is pinned rather than only the values: a LinkedHashMap that grew a member
        // or reordered one changes the wire just as surely as a changed value.
        val snapshot = QuotaSnapshot(
            fiveHour = QuotaWindow(14.0, 1_788_010_000L, 18_000L),
            sevenDay = QuotaWindow(42.5, 1_788_020_000L, 604_800L),
        )
        val out = headers.forClient(snapshot)
        assertEquals(
            listOf(
                "anthropic-ratelimit-unified-5h-utilization",
                "anthropic-ratelimit-unified-5h-reset",
                "anthropic-ratelimit-unified-7d-utilization",
                "anthropic-ratelimit-unified-7d-reset",
                "anthropic-ratelimit-unified-status",
            ),
            out.keys.toList(),
            "the plain -reset is OPT-IN: it must never appear on a default response",
        )
    }

    @Test
    fun `a refusal states rejected and the plain reset, which is the member a client reads`() {
        val snapshot = QuotaSnapshot(QuotaWindow(100.0, 1_788_010_000L, 18_000L), null, null, now)
        val out = headers.forClient(snapshot, QuotaStatus.REJECTED, DEADLINE)
        assertEquals("rejected", out["anthropic-ratelimit-unified-status"])
        assertEquals(DEADLINE.toString(), out["anthropic-ratelimit-unified-reset"])
        // The per-window members ride along: the deadline says WHEN, the window says WHY.
        assertEquals("1.0000", out["anthropic-ratelimit-unified-5h-utilization"])
    }

    @Test
    fun `an explicit status is written even with NO window, where the default writes nothing`() {
        // The one deliberate divergence from the default. A refusal has to be stated even when the
        // head tracks no window at all, because on a refusal the deadline IS the message.
        assertEquals(emptyMap<String, String>(), headers.forClient(QuotaSnapshot()), "default stays empty")
        assertEquals(
            mapOf("anthropic-ratelimit-unified-status" to "rejected"),
            headers.forClient(QuotaSnapshot(), QuotaStatus.REJECTED),
        )
    }

    @Test
    fun `the plain reset is opt-in and absent from every allowed response`() {
        val snapshot = QuotaSnapshot(QuotaWindow(14.0, 1_788_010_000L, 18_000L), null, null, now)
        assertNull(headers.forClient(snapshot)["anthropic-ratelimit-unified-reset"])
        assertEquals(
            DEADLINE.toString(),
            headers.forClient(snapshot, QuotaStatus.ALLOWED, DEADLINE)["anthropic-ratelimit-unified-reset"],
        )
    }
}

/** V4-51: a deadline, distinct from the window resets above so a swap is visible. */
private const val DEADLINE = 1_788_030_000L
