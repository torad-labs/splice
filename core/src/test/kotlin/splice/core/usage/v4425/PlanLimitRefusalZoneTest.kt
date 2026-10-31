// NEW: V4-425 — the line every spent plan window ends a turn with says its reset the way a person reads it: the
// machine's own month, day, clock time and zone abbreviation (LocalTimeText), never the ISO instant. V4-233 and V4-234
// wrote "used up until 2026-10-05T00:00:00Z" into text Claude Code prints to the developer, so a machine in Tokyo read
// midnight where its own clock said 9:00 AM. Failure-handling slice 2: the line is SpliceLines' own, written from the
// cause the plan headers made (a limit lifting at the reset they named, in the window they named), and PlanLimit no
// longer words a refusal. Every case names its zone, so none passes by the accident of the runner's.
package splice.core.usage.v4425

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.ClientWire
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.LineFacts
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.RequestFacts
import splice.core.failure.SpliceLines
import splice.core.util.LocalTimeText
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

private val RESET = Instant.parse("2026-10-05T00:00:00Z")
private val ISO_INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")

// Claude Code 2.1.257's stop phrases, the list RateLimitRefusalClientContractTest sweeps.
private val CLIENT_STOP_PHRASES = listOf(
    "service_spend_limit_reached",
    "exceeded_limit",
    "credits_required",
    "usage credits are required",
    "extra usage is required",
    "out_of_credits",
)

/** Runs [block] with the machine's zone set to [zone], and puts the old one back. */
private fun <T> inZone(zone: String, block: () -> T): T {
    val saved = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone(zone))
    try {
        return block()
    } finally {
        TimeZone.setDefault(saved)
    }
}

class PlanLimitRefusalZoneTest {
    private val facts =
        LineFacts("claude-splice", null, null, local = false, RequestFacts(false, false), RESET.minusSeconds(3_600))

    /** The line a turn ends with when the plan headers named [window] spent until [RESET], before any answer. */
    private fun said(window: LimitWindow, lines: SpliceLines): String {
        val cause = FailureCause.Provider.Limited(Lift.At(RESET, firm = true, LiftSource.RESET_HEADER), window)
        val at = Position(Moment.BEFORE_COMMIT, streamBegan = false)
        return lines.ending(cause, at, ClientWire.answer(cause, at), facts).text
    }

    private fun said(window: LimitWindow, zone: String) = said(window, SpliceLines(LocalTimeText(ZoneId.of(zone))))

    @Test
    fun `the reset is said in the zone it is given, with that zone's abbreviation`() {
        val tokyo = said(LimitWindow.WEEKLY, "Asia/Tokyo")
        val chicago = said(LimitWindow.FIVE_HOUR, "America/Chicago")

        assertTrue("Oct 5, 9:00 AM JST" in tokyo, tokyo)
        assertTrue("Oct 4, 7:00 PM CDT" in chicago, chicago)
    }

    @Test
    fun `no zone and no window brings back the ISO form, an em dash or a phrase that ends a client's wait`() {
        val zones = listOf("Asia/Tokyo", "America/Chicago", "UTC", "Asia/Kolkata", "Pacific/Auckland")
        val cases = zones.flatMap { zone -> LimitWindow.entries.map { window -> zone to window } }
        for ((zone, window) in cases) {
            val text = said(window, zone)
            val at = "$window in $zone: $text"
            assertTrue(LocalTimeText(ZoneId.of(zone)).at(RESET.epochSecond) in text, "the line names the reset, $at")
            assertFalse(ISO_INSTANT.containsMatchIn(text), "the reset is not an ISO instant, $at")
            assertFalse('—' in text, "no em dash in text the client shows, $at")
            CLIENT_STOP_PHRASES.forEach { phrase ->
                assertFalse(phrase in text.lowercase(), "'$phrase' would end a persistent client's wait, $at")
            }
        }
    }

    @Test
    fun `no zone given reads the machine's own, so the same line changes hour with the machine`() {
        val tokyo = inZone("Asia/Tokyo") { said(LimitWindow.FIVE_HOUR, SpliceLines()) }
        val chicago = inZone("America/Chicago") { said(LimitWindow.FIVE_HOUR, SpliceLines()) }

        assertTrue("Oct 5, 9:00 AM JST" in tokyo, tokyo)
        assertTrue("Oct 4, 7:00 PM CDT" in chicago, chicago)
        assertFalse(ISO_INSTANT.containsMatchIn(tokyo + chicago), tokyo + chicago)
    }
}
