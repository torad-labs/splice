// NEW (restructure PR 6 §2.7, home of the nf_04 wall): the Retry-After parser's own contract,
// pinned on the class rather than through a rate-limited upstream turn. Both RFC 7231 forms, the
// wall-clock conversion of the date form, the past-date clamp, and the garbage-stays-null contract.
// The saturation arms live beside the cooldown in UpstreamClientRateLimitTest, where the wrapped
// delay was first observed; this file owns the parse itself.
package splice.upstream.retry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.util.WallClock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class RetryAfterTest {

    private val retryAfter = RetryAfter()
    private val nowMs = 1_700_000_000_000L
    private val clock = WallClock { nowMs }

    private fun httpDate(epochMs: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))

    @Test
    fun `the seconds form converts to milliseconds, whitespace tolerated`() {
        assertEquals(30_000L, retryAfter.retryAfterMs("30", clock))
        assertEquals(0L, retryAfter.retryAfterMs("0", clock))
        assertEquals(5_000L, retryAfter.retryAfterMs("  5 ", clock))
    }

    @Test
    fun `a negative seconds value is not a delay`() {
        assertNull(retryAfter.retryAfterMs("-5", clock))
    }

    @Test
    fun `absent, blank and garbage stay null so the backoff curve decides`() {
        assertNull(retryAfter.retryAfterMs(null, clock))
        assertNull(retryAfter.retryAfterMs("", clock))
        assertNull(retryAfter.retryAfterMs("   ", clock))
        assertNull(retryAfter.retryAfterMs("soon", clock))
        assertNull(retryAfter.retryAfterMs("30s", clock))
        assertNull(retryAfter.retryAfterMs("1.5", clock))
    }

    @Test
    fun `the HTTP-date form is honoured against the wall clock - NF-04`() {
        // Cloudflare and gateway fronts emit the date form; before NF-04 it parsed to null and the
        // server's pushback was silently replaced by the 20s guess.
        assertEquals(90_000L, retryAfter.retryAfterMs(httpDate(nowMs + 90_000L), clock))
    }

    @Test
    fun `an HTTP-date in the past clamps to zero, never to null - NF-04`() {
        // Zero is "no wait" — a real verdict. Null would send the caller down the garbage path and
        // arm the default guess for a header that was perfectly well-formed.
        assertEquals(0L, retryAfter.retryAfterMs(httpDate(nowMs - 3_600_000L), clock))
    }

    @Test
    fun `a malformed date is garbage, not a parse crash`() {
        assertNull(retryAfter.retryAfterMs("Wed, 32 Oct 2026 07:28:00 GMT", clock))
        assertNull(retryAfter.retryAfterMs("2026-10-21T07:28:00Z", clock))
    }
}
