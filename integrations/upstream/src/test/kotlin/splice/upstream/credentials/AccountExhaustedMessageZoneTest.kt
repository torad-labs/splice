// NEW: V4-433, rewritten by failure-handling slice 2. The line a person reads when every login of a head is at its
// limit says the earliest reset the pool names the way a person reads it: the machine's month, day, clock time and
// zone abbreviation, never the ISO instant. V4-433 found "earliest reset is 2026-09-16T20:02:52Z" in what a client
// printed, so a machine in Tokyo read the evening where its own clock said 5:02 AM. The pool no longer words it:
// admission ends the turn on the limit the pool names, its earliest reset as AccountResetText.normalizedInstant reads
// it and no window, and the head's voice writes the line from that cause. Each case names its zone. AccountResetText.format
// is the log and journal spelling and stays ISO.
package splice.upstream.credentials

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.HeadVoice
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.VoiceFacts
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

private val MUSE_RESET = Instant.parse("2026-09-16T20:02:52Z").epochSecond
private val ISO_INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")
private val CLOCK_TIME = Regex("""\d{1,2}:\d{2}""")

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

class AccountExhaustedMessageZoneTest {
    /** The head's voice, its times read in [zone], at the moment the pool's reset names. */
    private fun voice(zone: ZoneId = ZoneId.systemDefault()) = HeadVoice(
        "claude",
        loginCommand = null,
        VoiceFacts(zone = zone),
        Clock.fixed(Instant.ofEpochSecond(MUSE_RESET), ZoneOffset.UTC),
    )

    /** The line a turn ends with, before any answer, when every login is at its limit and the pool names [reset]. */
    private fun said(reset: Long?, voice: HeadVoice): String {
        val lift = reset?.let { Lift.At(AccountResetText.normalizedInstant(it), firm = true, LiftSource.RESET_HEADER) }
        val cause = FailureCause.Provider.Limited(lift ?: Lift.Unstated, window = null)
        val at = Position(Moment.BEFORE_COMMIT, streamBegan = false)
        return voice.anyTurn.ending(cause, at).text
    }

    private fun said(reset: Long?, zone: String) = said(reset, voice(ZoneId.of(zone)))

    @Test
    fun `the earliest reset is said in the zone's hour and abbreviation, not as an ISO instant`() {
        val tokyo = said(MUSE_RESET, "Asia/Tokyo")
        val chicago = said(MUSE_RESET, "America/Chicago")

        assertTrue("Sep 17, 5:02 AM JST" in tokyo, tokyo)
        assertTrue("Sep 16, 3:02 PM CDT" in chicago, chicago)
        assertFalse(ISO_INSTANT.containsMatchIn(tokyo + chicago), "the reset is not an ISO instant: $tokyo $chicago")
    }

    @Test
    fun `no zone given reads the machine's own`() {
        val tokyo = inZone("Asia/Tokyo") { said(MUSE_RESET, voice()) }
        val chicago = inZone("America/Chicago") { said(MUSE_RESET, voice()) }

        assertTrue("Sep 17, 5:02 AM JST" in tokyo, tokyo)
        assertTrue("Sep 16, 3:02 PM CDT" in chicago, chicago)
    }

    @Test
    fun `a pool that names no reset says no time`() {
        val line = said(null, "Asia/Tokyo")

        assertFalse(CLOCK_TIME.containsMatchIn(line), "no reset was named, so no time is said: $line")
    }

    @Test
    fun `a reset past the wire range is said as the range's end, and the line is still written`() {
        val line = said(Long.MAX_VALUE, "UTC")

        assertTrue("Dec 31, 11:59 PM UTC" in line, line)
    }

    @Test
    fun `the words around the time carry no dash and no stop phrase`() {
        for (reset in listOf(MUSE_RESET, null)) {
            val line = said(reset, "Asia/Kolkata")

            assertFalse('—' in line, "no em dash in text the client shows: $line")
            CLIENT_STOP_PHRASES.forEach { phrase ->
                assertFalse(phrase in line.lowercase(), "'$phrase' would end a persistent client's wait: $line")
            }
        }
    }
}
