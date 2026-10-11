// NEW: V4-433 — the message a client prints when every account is exhausted says the earliest reset the way a person
// reads it, the rule V4-419 gave the trace, V4-425 the plan refusal and V4-428 the burst 429. It carried the ISO instant
// raw ("earliest reset is 2026-09-16T20:02:52Z"), so a machine in Tokyo read the evening where its own clock said
// 5:02 AM. AccountResetText.format is the log and journal spelling and stays ISO. Each case names its zone; the words
// around the time (the dash-free wording V4-234 fixed, no phrase that ends a persistent client's wait) and the
// null case are pinned unchanged beside it.
package splice.upstream.credentials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.TimeZone

private val MUSE_RESET = Instant.parse("2026-09-16T20:02:52Z").epochSecond
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

class AccountExhaustedMessageZoneTest {

    @Test
    fun `the earliest reset is said in the machine's hour and zone, not as an ISO instant`() {
        val tokyo = inZone("Asia/Tokyo") { Selection.Exhausted(MUSE_RESET).message }
        val chicago = inZone("America/Chicago") { Selection.Exhausted(MUSE_RESET).message }

        assertEquals("all OAuth accounts are exhausted; earliest reset is Sep 17, 5:02 AM JST", tokyo)
        assertEquals("all OAuth accounts are exhausted; earliest reset is Sep 16, 3:02 PM CDT", chicago)
        assertFalse(ISO_INSTANT.containsMatchIn(tokyo + chicago), "the reset is not an ISO instant: $tokyo $chicago")
    }

    @Test
    fun `an exhausted pool that names no reset says unknown, as before`() {
        val message = inZone("Asia/Tokyo") { Selection.Exhausted(null).message }

        assertEquals("all OAuth accounts are exhausted; earliest reset is unknown", message)
    }

    @Test
    fun `the words around the time carry no dash and no stop phrase`() {
        val message = inZone("Asia/Kolkata") { Selection.Exhausted(MUSE_RESET).message }

        assertFalse('—' in message, "no em dash in text the client shows: $message")
        CLIENT_STOP_PHRASES.forEach { phrase ->
            assertFalse(phrase in message.lowercase(), "'$phrase' would end a persistent client's wait: $message")
        }
    }
}
