// NEW: V4-428 — the fail-fast 429 a burst leaves behind says the provider's reset the way a person reads it, the same
// rule V4-419 gave the trace and V4-425 gave the plan refusal. The live muse episode's body names a reset at
// 2026-09-16T20:02:52Z, and the sentence Claude Code printed carried that instant raw, so a machine in Tokyo read the
// evening where its own clock said 5:02 AM. Each case names its zone; the wording V4-61 chose (the window is REPORTED,
// never asserted as the deadline), the no-reset branch and the plan branch are pinned unchanged beside the new time.
package splice.upstream.v4428

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.LocalTimeText
import splice.core.util.WallClock
import splice.upstream.RetryNotice
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.RateLimitTurn
import splice.upstream.transport.UpstreamFailed
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

private val WALL = Instant.parse("2026-09-16T13:34:32Z").toEpochMilli()
private val ISO_INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")

// The body the live muse 429 carried: a window reset 6h28m out, named as an ISO instant in the message.
private const val MUSE_BODY = """{"error":{"message":"Subscription quota exhausted. Your usage window resets """ +
    """at 2026-09-16T20:02:52Z","type":"rate_limit_error"},"type":"error"}"""

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

class BurstResetZoneTest {

    /** With no [zone] the constructor's own default is what runs, which is the machine's. */
    private fun fresh(zone: String? = null) = when (zone) {
        null -> RateLimitCooldown(ElapsedClock { 0L }, WallClock { WALL })
        else -> RateLimitCooldown(ElapsedClock { 0L }, WallClock { WALL }, times = LocalTimeText(ZoneId.of(zone)))
    }

    private fun armedBy(body: String, cooldown: RateLimitCooldown = fresh()): RateLimitCooldown {
        cooldown.rateLimitedPlan(
            pushbackMs = 5_301_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = false),
            canRetry = false,
            onRetry = RetryNotice {},
            nextRefreshed = false,
            body = body,
        )
        return cooldown
    }

    private fun failFast(cooldown: RateLimitCooldown): String =
        assertThrows<UpstreamFailed> { cooldown.failFastIfArmed(RetryNotice {}) }.body

    @Test
    fun `a burst that names its reset says it in the machine's hour and zone, not as an ISO instant`() {
        val tokyo = inZone("Asia/Tokyo") { failFast(armedBy(MUSE_BODY)) }
        val chicago = inZone("America/Chicago") { failFast(armedBy(MUSE_BODY)) }

        assertTrue(
            "the upstream reports its quota window resets at Sep 17, 5:02 AM JST. " +
                "If this keeps happening, that is the real deadline." in tokyo,
            tokyo,
        )
        assertTrue("resets at Sep 16, 3:02 PM CDT. If this keeps happening" in chicago, chicago)
        assertFalse(ISO_INSTANT.containsMatchIn(tokyo + chicago), "the reset is not an ISO instant: $tokyo $chicago")
    }

    @Test
    fun `a cooldown handed a zone says the reset in it, whatever the machine's is`() {
        val tokyo = inZone("America/Chicago") { failFast(armedBy(MUSE_BODY, fresh("Asia/Tokyo"))) }
        val auckland = inZone("Asia/Tokyo") { failFast(armedBy(MUSE_BODY, fresh("Pacific/Auckland"))) }

        assertTrue("resets at Sep 17, 5:02 AM JST." in tokyo, tokyo)
        assertTrue("resets at Sep 17, 8:02 AM NZST." in auckland, auckland)
    }

    @Test
    fun `a held plan window is said in the zone the cooldown was handed too`() {
        val reset = Instant.parse("2026-10-05T00:00:00Z").epochSecond

        val body = inZone("America/Chicago") {
            val cooldown = fresh("Asia/Tokyo")
            cooldown.planHold.hold(PlanLimit("five_hour", reset), RetryNotice {})
            cooldown.arm(pushbackMs = 5_301_000L)
            failFast(cooldown)
        }

        assertTrue("5-hour window is used up until Oct 5, 9:00 AM JST, and" in body, body)
    }

    @Test
    fun `V4-61's wording and V4-234's limits stay, so the reset is reported and no dash or stop phrase enters`() {
        val body = inZone("Asia/Kolkata") { failFast(armedBy(MUSE_BODY)) }

        assertTrue("holding retries for 120s, and the upstream reports its quota window resets" in body, body)
        assertTrue("\"type\":\"rate_limit_error\"" in body && "\"detail\"" !in body, body)
        assertFalse('—' in body, "no em dash in text the client shows: $body")
        CLIENT_STOP_PHRASES.forEach { phrase ->
            assertFalse(phrase in body.lowercase(), "'$phrase' would end a persistent client's wait: $body")
        }
    }

    @Test
    fun `a burst that names no reset says no time at all`() {
        val body = inZone("Asia/Tokyo") { failFast(armedBy("""{"error":{"message":"slow down"}}""")) }

        assertTrue(body.contains("this gateway is holding retries for 120s to avoid a retry wave"), body)
        assertFalse("resets at" in body, body)
    }

    @Test
    fun `a held plan window keeps its own sentence, in the same machine zone`() {
        val reset = Instant.parse("2026-10-05T00:00:00Z").epochSecond

        val body = inZone("Asia/Tokyo") {
            val cooldown = fresh()
            cooldown.planHold.hold(PlanLimit("five_hour", reset), RetryNotice {})
            cooldown.arm(pushbackMs = 5_301_000L)
            failFast(cooldown)
        }

        assertTrue("5-hour window is used up until Oct 5, 9:00 AM JST, and this gateway is holding" in body, body)
        assertFalse(ISO_INSTANT.containsMatchIn(body), body)
    }
}
