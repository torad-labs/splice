// NEW: V4-233's reading of Anthropic's unified plan family. A spent PLAN window is exactly three
// members together: status `rejected`, a window claim, and the plain reset still ahead. Every other
// shape is a burst or nothing, and must stay on V4-61's path.
package campaign.v4233

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.usage.FIVE_HOURS_SECONDS
import splice.core.usage.PlanLimit
import splice.core.usage.PlanLimits
import splice.core.usage.QuotaHeaderRead
import splice.core.usage.SEVEN_DAYS_SECONDS

class PlanLimitOfTest {
    private val now = 1_790_000_000L

    private fun headers(vararg pairs: Pair<String, String>): QuotaHeaderRead {
        val map = pairs.toMap()
        return QuotaHeaderRead { name -> map[name] }
    }

    private fun spent(claim: String, reset: String) = headers(
        "anthropic-ratelimit-unified-status" to "rejected",
        "anthropic-ratelimit-unified-representative-claim" to claim,
        "anthropic-ratelimit-unified-reset" to reset,
    )

    @Test
    fun `a rejected five-hour claim with its reset ahead is a spent plan window`() {
        assertEquals(PlanLimit("five_hour", now + 3_600), PlanLimits.of(spent("five_hour", "${now + 3_600}"), now))
    }

    @Test
    fun `every seven-day claim is a plan window a week long`() {
        assertEquals(PlanLimit("seven_day", now + 86_400), PlanLimits.of(spent("seven_day", "${now + 86_400}"), now))
        assertEquals(
            PlanLimit("seven_day_opus", now + 86_400),
            PlanLimits.of(spent("seven_day_opus", "${now + 86_400}"), now),
        )
    }

    @Test
    fun `a reset further out than one whole window is held no longer than the window`() {
        assertEquals(
            PlanLimit("five_hour", now + FIVE_HOURS_SECONDS),
            PlanLimits.of(spent("five_hour", "${now + 10 * 3_600}"), now),
            "a malformed reset must not hold a head past the window its claim names",
        )
        assertEquals(
            PlanLimit("seven_day", now + SEVEN_DAYS_SECONDS),
            PlanLimits.of(spent("seven_day", "${now + 30 * 86_400}"), now),
        )
    }

    @Test
    fun `a reset spelled in milliseconds is the same instant`() {
        assertEquals(
            PlanLimit("five_hour", now + 3_600),
            PlanLimits.of(spent("five_hour", "${(now + 3_600) * 1_000}"), now),
        )
    }

    @Test
    fun `nothing short of all three members is a plan limit`() {
        val cases = mapOf(
            "allowed" to headers(
                "anthropic-ratelimit-unified-status" to "allowed",
                "anthropic-ratelimit-unified-representative-claim" to "five_hour",
                "anthropic-ratelimit-unified-reset" to "${now + 3_600}",
            ),
            "allowed_warning" to headers(
                "anthropic-ratelimit-unified-status" to "allowed_warning",
                "anthropic-ratelimit-unified-representative-claim" to "five_hour",
                "anthropic-ratelimit-unified-reset" to "${now + 3_600}",
            ),
            "no claim" to headers(
                "anthropic-ratelimit-unified-status" to "rejected",
                "anthropic-ratelimit-unified-reset" to "${now + 3_600}",
            ),
            "overage is a spend bucket, not a window" to spent("overage", "${now + 3_600}"),
            "no reset" to headers(
                "anthropic-ratelimit-unified-status" to "rejected",
                "anthropic-ratelimit-unified-representative-claim" to "five_hour",
            ),
            "a reset already passed" to spent("five_hour", "${now - 1}"),
            "a reset that is not a number" to spent("five_hour", "soon"),
            "no unified family at all" to headers("retry-after" to "30"),
        )
        cases.forEach { (why, h) -> assertNull(PlanLimits.of(h, now), why) }
    }

    @Test
    fun `a claim reads in words, and a model-scoped week names its model`() {
        assertEquals("5-hour", PlanLimit("five_hour", now).windowWords)
        assertEquals("7-day", PlanLimit("seven_day", now).windowWords)
        assertEquals("7-day Opus", PlanLimit("seven_day_opus", now).windowWords)
        assertEquals("7-day Sonnet", PlanLimit("seven_day_sonnet", now).windowWords)
    }
}
