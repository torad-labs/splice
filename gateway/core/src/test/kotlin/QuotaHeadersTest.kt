// NEW: quota windows on the wire, both directions (see QuotaHeaders). The client side is pinned to
// what Claude Code reads: utilization as a 0..1 fraction, reset as epoch seconds, plus the status
// header. The upstream side covers Anthropic's unified family; vendor families live on QuotaHeaderFamily.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.usage.QuotaHeaderRead
import splice.core.usage.QuotaHeaders
import splice.core.usage.QuotaJson
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
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
        assertEquals(1_788_010_000L, snapshot.fiveHour!!.resetsAt)
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
}
