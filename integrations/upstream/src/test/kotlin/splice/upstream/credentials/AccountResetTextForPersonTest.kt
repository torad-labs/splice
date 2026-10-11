// NEW: V4-433 — AccountResetText.forPerson is the one place a reset becomes words for a client: the machine's own
// month, day, clock time and zone abbreviation through LocalTimeText, "unknown" when none was named, clamped to the
// four-digit year range so an absurd upstream instant cannot overflow the formatter. Handed a zone it says the reset in
// that zone whatever the machine's is, the seam a test uses to name one.
package splice.upstream.credentials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.util.LocalTimeText
import java.time.Instant
import java.time.ZoneId

private val MUSE_RESET = Instant.parse("2026-09-16T20:02:52Z").epochSecond
private val ISO_INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")

private fun said(epochSeconds: Long?, zone: String) =
    AccountResetText.forPerson(epochSeconds, LocalTimeText(ZoneId.of(zone)))

class AccountResetTextForPersonTest {

    @Test
    fun `a zone it is handed decides the hour and abbreviation`() {
        assertEquals("Sep 17, 5:02 AM JST", said(MUSE_RESET, "Asia/Tokyo"))
        assertEquals("Sep 16, 3:02 PM CDT", said(MUSE_RESET, "America/Chicago"))
        assertEquals("Sep 17, 1:32 AM IST", said(MUSE_RESET, "Asia/Kolkata"))
    }

    @Test
    fun `no reset named says unknown, in the same word format uses`() {
        assertEquals("unknown", said(null, "UTC"))
        assertEquals(AccountResetText.format(null), said(null, "UTC"))
    }

    @Test
    fun `an instant past either end of the wire range is clamped to it, and never throws`() {
        assertEquals("Dec 31, 11:59 PM UTC", said(Long.MAX_VALUE, "UTC"))
        assertEquals("Jan 1, 12:00 AM UTC", said(Long.MIN_VALUE, "UTC"))
    }

    @Test
    fun `it never spells the ISO instant, which format keeps for the logs`() {
        assertFalse(ISO_INSTANT.containsMatchIn(said(MUSE_RESET, "UTC")), said(MUSE_RESET, "UTC"))
        assertEquals("2026-09-16T20:02:52Z", AccountResetText.format(MUSE_RESET))
    }
}
