package splice.provider.codex.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.failure.LoginOwner
import splice.core.usage.PlanLimit
import splice.provider.codex.failure.CodexReplies
import splice.upstream.failure.FailedReply
import splice.upstream.failure.SentKey
import java.time.Instant

private const val NOW_S = 1_790_584_000L
private const val SIX_DAYS_S = 6L * 24 * 3_600

// The two windows ChatGPT names, as the row counts them: seconds.
private const val SEVEN_DAYS_S = 7L * 24 * 3_600
private const val FIVE_HOURS_S = 5L * 3_600

/** ChatGPT names a spent plan window in the 429 BODY. Its reader (CodexReplies, since failure-handling slice 3; the
 *  login provider until then) reads it into the same [PlanLimit] the transport already holds a turn on; a burst 429 and
 *  any other body read null. */
class CodexPlanLimitBodyTest {
    private fun planLimit(body: String): PlanLimit? = CodexReplies.planLimit(
        FailedReply(429, emptyMap(), body, LoginOwner.SPLICE, SentKey { _, _ -> false }, Instant.ofEpochSecond(NOW_S)),
    )

    private fun body(vararg fields: String) =
        """{"error":{"type":"usage_limit_reached","plan_type":"pro",${fields.joinToString(",")}}}"""

    @Test
    fun `the live weekly body reads as a seven-day plan limit at its reset`() {
        val reset = NOW_S + SIX_DAYS_S
        val text = body("\"resets_at\":$reset", "\"resets_in_seconds\":$SIX_DAYS_S", "\"limit_window_minutes\":10080")

        // V4-444: the window's LENGTH crosses in seconds, so a surface can name the window ("Week") and not only
        // the instant it comes back. The body's minutes stop at this reader.
        assertEquals(PlanLimit("seven_day", reset, windowSeconds = SEVEN_DAYS_S), planLimit(text))
    }

    @Test
    fun `a window the body does not name has no length, rather than a length of nothing`() {
        val named = body("\"resets_at\":${NOW_S + 3_600}", "\"limit_window_minutes\":300")
        val unnamed = body("\"resets_at\":${NOW_S + 3_600}")

        assertEquals(FIVE_HOURS_S, planLimit(named)?.windowSeconds)
        assertNull(
            planLimit(unnamed)?.windowSeconds,
            "no window named is no length: a zero would read as a window of no length",
        )
    }

    @Test
    fun `a five-hour window, an odd window and an unnamed window are named as such`() {
        fun claim(vararg extra: String) = planLimit(body("\"resets_at\":${NOW_S + 3_600}", *extra))?.claim

        assertEquals("five_hour", claim("\"limit_window_minutes\":300"))
        assertEquals("60-minute", claim("\"limit_window_minutes\":60"))
        assertEquals("usage", claim())
    }

    @Test
    fun `only a duration is enough, measured from now`() {
        val text = body("\"resets_in_seconds\":7200", "\"limit_window_minutes\":300")

        assertEquals(PlanLimit("five_hour", NOW_S + 7_200, windowSeconds = FIVE_HOURS_S), planLimit(text))
    }

    @Test
    fun `a reset further out than one whole window is bounded by that window`() {
        val text = body("\"resets_at\":${NOW_S + 40 * 24 * 3_600L}", "\"limit_window_minutes\":10080")

        assertEquals(NOW_S + SIX_DAYS_S + 24 * 3_600L, planLimit(text)?.resetEpochSeconds)
    }

    @Test
    fun `bodies that name no spent window read null and keep the burst schedule`() {
        val passed = body("\"resets_at\":${NOW_S - 10}", "\"limit_window_minutes\":10080")
        val burst = """{"error":{"type":"rate_limit_exceeded","message":"slow down","resets_at":${NOW_S + 60}}}"""
        val noReset = body("\"limit_window_minutes\":10080")

        assertNull(planLimit(passed), "a reset already passed names nothing")
        assertNull(planLimit(burst), "another error type is not a spent plan")
        assertNull(planLimit(noReset), "no reset, no hold")
        assertNull(planLimit("upstream connect error"), "not JSON at all")
    }
}
