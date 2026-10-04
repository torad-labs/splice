// NEW: V4-419 — the one rule for a time a person reads in product text: the machine's own zone, said with that
// zone's abbreviation. `splice status` hard-coded America/Chicago and printed " CT" to every user (StatusTable, from
// V4-398), so a reset read in Tokyo said the wrong hour under the wrong name. Every case names its zone, so the
// rule cannot pass by accident of the machine the tests run on.
package splice.core.util.v4419

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.util.LocalTimeText
import java.time.Instant
import java.time.ZoneId

// 2026-10-05T00:00:00Z and 2026-12-05T00:00:00Z: one instant in daylight time, one in standard time.
private val OCTOBER = Instant.parse("2026-10-05T00:00:00Z").epochSecond
private val DECEMBER = Instant.parse("2026-12-05T00:00:00Z").epochSecond

class LocalTimeTextTest {

    private fun said(zone: String, epochSeconds: Long) = LocalTimeText(ZoneId.of(zone)).at(epochSeconds)

    @Test
    fun `an instant is said in the zone it was given, with that zone's own abbreviation`() {
        assertEquals("Oct 4, 7:00 PM CDT", said("America/Chicago", OCTOBER))
        assertEquals("Oct 5, 9:00 AM JST", said("Asia/Tokyo", OCTOBER))
        assertEquals("Oct 5, 12:00 AM UTC", said("UTC", OCTOBER))
    }

    @Test
    fun `the abbreviation follows daylight time instead of being a fixed label`() {
        assertEquals("Dec 4, 6:00 PM CST", said("America/Chicago", DECEMBER))
        assertEquals("Oct 4, 7:00 PM CDT", said("America/Chicago", OCTOBER))
    }

    @Test
    fun `a zone that is not Chicago never says CT`() {
        val tokyo = said("Asia/Tokyo", OCTOBER)
        assertFalse("CT" in tokyo || "CDT" in tokyo || "CST" in tokyo, tokyo)
    }

    @Test
    fun `no zone given reads the machine's own`() {
        assertEquals(
            LocalTimeText(ZoneId.systemDefault()).at(OCTOBER),
            LocalTimeText().at(OCTOBER),
        )
    }
}
