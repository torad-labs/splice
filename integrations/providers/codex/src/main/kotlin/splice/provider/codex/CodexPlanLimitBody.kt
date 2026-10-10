package splice.provider.codex

import splice.core.usage.PlanLimit
import java.util.concurrent.TimeUnit

// V4-377: ChatGPT names a spent plan window in the 429 body: {"type":"usage_limit_reached",
// "plan_type":"pro","resets_at":<epoch seconds>,"resets_in_seconds":<n>,"limit_window_minutes":<n>}.
// Read by pattern, as RateLimitCooldown reads the same body for the provider reset: a text that is
// not that JSON simply matches nothing, so there is no parse to fail.
private val USAGE_LIMIT_RE = Regex(""""type"\s*:\s*"usage_limit_reached"""")
private val RESETS_AT_RE = Regex(""""resets_at"\s*:\s*(\d{9,})""")
private val RESETS_IN_RE = Regex(""""resets_in_seconds"\s*:\s*(\d+)""")
private val WINDOW_MINUTES_RE = Regex(""""limit_window_minutes"\s*:\s*(\d+)""")

// The 5-hour ChatGPT plan window, as its 429 body writes limit_window_minutes.
private const val FIVE_HOUR_MINUTES = 300L

// The weekly ChatGPT plan window (7 days), in the same unit.
private const val SEVEN_DAY_MINUTES = 10_080L

/** What a 429 body says about the plan window it spent, split out of CodexAuthProvider. */
internal class CodexPlanLimitBody {
    /** V4-377: a 429 `usage_limit_reached` naming a reset in the future is a spent plan window, not a
     *  burst: the reset is bounded by the window it names (a reset further out than one whole window
     *  is not one this window can name), and one already passed names nothing. The window is the
     *  body's `limit_window_minutes`: 300 is the 5-hour claim, 10080 the 7-day, any other length is
     *  named in minutes, and none names the plain usage window. */
    fun read(body: String, nowEpochSeconds: Long): PlanLimit? {
        if (!USAGE_LIMIT_RE.containsMatchIn(body)) return null
        val reset = namedReset(body, nowEpochSeconds)?.takeIf { it > nowEpochSeconds } ?: return null
        val minutes = number(WINDOW_MINUTES_RE, body)?.takeIf { it > 0L }
        val bounded = minutes?.let { minOf(reset, nowEpochSeconds + TimeUnit.MINUTES.toSeconds(it)) } ?: reset
        return PlanLimit(windowClaim(minutes), bounded)
    }

    /** The reset the body names: an absolute instant first, else a duration from [nowEpochSeconds]. */
    private fun namedReset(body: String, nowEpochSeconds: Long): Long? =
        number(RESETS_AT_RE, body) ?: number(RESETS_IN_RE, body)?.let { nowEpochSeconds + it }

    private fun number(pattern: Regex, body: String): Long? = pattern.find(body)?.groupValues?.get(1)?.toLongOrNull()

    private fun windowClaim(minutes: Long?): String = when (minutes) {
        null -> "usage"
        FIVE_HOUR_MINUTES -> "five_hour"
        SEVEN_DAY_MINUTES -> "seven_day"
        else -> "$minutes-minute"
    }
}
