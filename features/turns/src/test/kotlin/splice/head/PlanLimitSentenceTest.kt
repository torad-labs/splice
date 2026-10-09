// NEW: the sentence a plan-limit ending speaks: the window in words, the reset the provider named in the zone
// it is handed, and what to do. It is one sentence for the turn that met the 429 and every turn held behind it, so the
// two cannot name different instants.
package splice.head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.usage.PlanLimit
import splice.core.util.ERR_SNIPPET
import splice.core.util.LocalTimeText
import splice.head.turn.OutcomeSentences
import java.time.Instant
import java.time.ZoneId

private val RESET = Instant.parse("2026-10-05T00:00:00Z").epochSecond

class PlanLimitSentenceTest {

    private fun said(claim: String, zone: String) =
        OutcomeSentences.planLimit(PlanLimit(claim, RESET), LocalTimeText(ZoneId.of(zone)))

    @Test
    fun `it names the window and the reset in the zone it is given`() {
        assertEquals(
            "this plan's 7-day window is used up until Oct 5, 9:00 AM JST; " +
                "retrying sooner cannot succeed, so wait for the reset, then retry",
            said("seven_day", "Asia/Tokyo"),
        )
        assertEquals(
            "this plan's 5-hour window is used up until Oct 4, 7:00 PM CDT; " +
                "retrying sooner cannot succeed, so wait for the reset, then retry",
            said("five_hour", "America/Chicago"),
        )
        assertTrue("7-day Opus window" in said("seven_day_opus", "UTC"), said("seven_day_opus", "UTC"))
    }

    @Test
    fun `no window claim can make it longer than a trace keeps, or quote a path or bytes`() {
        // The unified header's claim is upstream text: `seven_day` plus any suffix names a window (PlanLimits), so
        // the last claim is as long as a hostile one can be, and the reset must still be the part that survives.
        val claims = listOf(
            "five_hour",
            "seven_day",
            "seven_day_opus",
            "seven_day_sonnet",
            "seven_day_" + "x".repeat(500),
        )
        val instant = LocalTimeText(ZoneId.of("Australia/Lord_Howe")).at(RESET)
        for (claim in claims) {
            val sentence = said(claim, "Australia/Lord_Howe")
            assertTrue("; " in sentence, "$claim: what happened, then after a semicolon what to do: $sentence")
            assertTrue(sentence.length <= ERR_SNIPPET, "${claim.take(20)}: cut at $ERR_SNIPPET loses the advice")
            assertTrue(instant in sentence, "${claim.take(20)}: the reset must survive: $sentence")
            assertFalse(sentence.any { it in "/\\{}<>\"`" } || "://" in sentence, "${claim.take(20)}: $sentence")
        }
    }

    @Test
    fun `the trace and the refusal the client is sent say the same reset the same way`() {
        val times = LocalTimeText(ZoneId.of("Asia/Tokyo"))
        val limit = PlanLimit("seven_day", RESET)

        val trace = OutcomeSentences.planLimit(limit, times)
        val refusal = limit.refusal(times = times)

        assertTrue("7-day window is used up until Oct 5, 9:00 AM JST" in trace, trace)
        assertTrue("7-day window is used up until Oct 5, 9:00 AM JST" in refusal, refusal)
    }
}
