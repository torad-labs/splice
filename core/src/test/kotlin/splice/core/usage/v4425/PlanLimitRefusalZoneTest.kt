// NEW: V4-425 — the refusal every spent plan window hands the client says its reset the way a person reads it: the
// machine's own month, day, clock time and zone abbreviation (LocalTimeText), never the ISO instant. V4-233 and V4-234
// wrote "used up until 2026-10-05T00:00:00Z" into text Claude Code prints to the developer, so a machine in Tokyo read
// midnight where its own clock said 9:00 AM. Every case names its zone, so none passes by the accident of the runner's;
// the holding clause and the words that keep a persistent client waiting are pinned unchanged beside the new time.
package splice.core.usage.v4425

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.usage.PlanLimit
import splice.core.util.LocalTimeText
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

private val RESET = Instant.parse("2026-10-05T00:00:00Z").epochSecond
private const val HOLD = "this gateway is holding retries for 90s"
private const val WAITS = "The session waits and resumes after the reset."
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

    private fun said(claim: String, zone: String, holding: String? = null) =
        PlanLimit(claim, RESET).refusal(holding, LocalTimeText(ZoneId.of(zone)))

    @Test
    fun `the reset is said in the zone it is given, with that zone's abbreviation`() {
        assertEquals(
            "Rate limit exceeded: the upstream reports this plan's 7-day window is used up until " +
                "Oct 5, 9:00 AM JST. $WAITS",
            said("seven_day", "Asia/Tokyo"),
        )
        assertEquals(
            "Rate limit exceeded: the upstream reports this plan's 5-hour window is used up until " +
                "Oct 4, 7:00 PM CDT. $WAITS",
            said("five_hour", "America/Chicago"),
        )
    }

    @Test
    fun `the holding clause follows the time exactly where it stood`() {
        assertEquals(
            "Rate limit exceeded: the upstream reports this plan's 5-hour window is used up until " +
                "Oct 5, 9:00 AM JST, and $HOLD. $WAITS",
            said("five_hour", "Asia/Tokyo", HOLD),
        )
    }

    @Test
    fun `no zone and no claim brings back the ISO form, an em dash or a phrase that ends a client's wait`() {
        val zones = listOf("Asia/Tokyo", "America/Chicago", "UTC", "Asia/Kolkata", "Pacific/Auckland")
        val claims = listOf("five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet")
        val cases = zones.flatMap { zone ->
            claims.flatMap { claim -> listOf(null, HOLD).map { Triple(zone, claim, it) } }
        }
        for ((zone, claim, holding) in cases) {
            val text = said(claim, zone, holding)
            val at = "$claim in $zone holding=$holding: $text"
            assertFalse(ISO_INSTANT.containsMatchIn(text), "the reset is not an ISO instant, $at")
            assertFalse('—' in text, "no em dash in text the client shows, $at")
            CLIENT_STOP_PHRASES.forEach { phrase ->
                assertFalse(phrase in text.lowercase(), "'$phrase' would end a persistent client's wait, $at")
            }
            assertTrue(text.endsWith(WAITS), at)
        }
    }

    @Test
    fun `no zone given reads the machine's own, so the same refusal changes hour with the machine`() {
        val limit = PlanLimit("five_hour", RESET)

        val tokyo = inZone("Asia/Tokyo") { limit.refusal() }
        val chicago = inZone("America/Chicago") { limit.refusal() }

        assertTrue("used up until Oct 5, 9:00 AM JST. $WAITS" in tokyo, tokyo)
        assertTrue("used up until Oct 4, 7:00 PM CDT. $WAITS" in chicago, chicago)
        assertFalse(ISO_INSTANT.containsMatchIn(tokyo + chicago), tokyo + chicago)
    }
}
