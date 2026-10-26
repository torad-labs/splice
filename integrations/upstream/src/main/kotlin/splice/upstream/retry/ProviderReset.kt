// NEW: failure-handling slice 2 (2026-10-26). The reset a provider's 429 states, read once for both of its readers:
// the cooldown, which turns it into a delay on its own clock (V4-47), and the cause a person's line is written from,
// which says it as the instant it is. The spellings moved here from RateLimitCooldown unchanged.
package splice.upstream.retry

import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.usage.PlanLimit
import java.time.Instant
import java.time.format.DateTimeParseException

/** The provider's own reset in a 429, as V4-47 reads it from the body and V4-233 from the plan window it names. */
internal object ProviderReset {
    /** When the limit a 429 names lifts, as the answer stated it: the reset of the plan window it named ([plan],
     *  read from its headers when [fromHeaders], else from its body), then the reset its [body] states, then none.
     *  A body's reset is the provider's statement, said as it stands; [answeredAtMs] measures a duration from when
     *  the answer came. */
    fun lift(plan: PlanLimit?, fromHeaders: Boolean, body: String, answeredAtMs: Long): Lift {
        if (plan != null) {
            val source = if (fromHeaders) LiftSource.RESET_HEADER else LiftSource.BODY
            return Lift.At(Instant.ofEpochSecond(plan.resetEpochSeconds), firm = true, source)
        }
        val stated = wallMs(body, answeredAtMs)?.takeIf { it > 0L } ?: return Lift.Unstated
        return Lift.At(Instant.ofEpochMilli(stated), firm = true, LiftSource.BODY)
    }

    /** The reset [body] states, as a wall-clock instant in epoch milliseconds, or null when it states none. An
     *  ABSOLUTE instant is preferred over a duration in every branch: it survives clock skew and it is the fact the
     *  operator needs to read. A duration is measured from [nowMs]. */
    fun wallMs(body: String?, nowMs: Long): Long? {
        if (body == null) return null
        val epochSeconds = RESET_EPOCH_RE.find(body)?.groupValues?.get(1)?.toLongOrNull()
        // The conventional rate-limit header's reset, when the body echoes it (OpenRouter's 429 carries
        // error.metadata.headers.X-RateLimit-Reset). Its unit varies by vendor, so the digits say it: an
        // epoch in milliseconds has 13 today and one in seconds 10, which three zeros make milliseconds.
        val headerResetMs = RESET_HEADER_RE.find(body)?.groupValues?.get(1)
            ?.padEnd(EPOCH_MS_DIGITS, '0')?.toLongOrNull()
        val iso = RESET_ISO_RE.find(body)?.groupValues?.get(1)
        val inSeconds = RESET_IN_RE.find(body)?.groupValues?.get(1)?.toLongOrNull()
        return epochSeconds?.times(MS_PER_S)
            ?: headerResetMs
            ?: iso?.let(::isoMs)
            ?: inSeconds?.let { nowMs + it * MS_PER_S }
    }

    // The ISO branch collapses to null DELIBERATELY. It parses a timestamp out of an UPSTREAM-CONTROLLED 429 body on
    // a best-effort basis, and the caller already treats "no reset was named" as a first-class outcome. A vendor
    // emitting a malformed instant is not a splice failure to diagnose, and logging one per 429 would be noise on
    // exactly the path an operator is already reading.
    private fun isoMs(iso: String): Long? = try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    // An epoch instant in milliseconds has 13 digits until the year 2286.
    private const val EPOCH_MS_DIGITS = 13

    // V4-47: the provider reset's spellings, tried in that order of authority. `resets_at` is epoch SECONDS (the
    // ChatGPT backend); the ISO form is what the muse body carries; `resets_in_seconds` is a duration and therefore
    // the weakest, since it is only as good as our clock at receipt. `X-RateLimit-Reset` is the conventional
    // header's name echoed in a body, quoted plainly or escaped inside a string the vendor relayed.
    private val RESET_EPOCH_RE = Regex(""""resets_at"\s*:\s*(\d{9,})""")
    private val RESET_HEADER_RE = Regex(
        """x-ratelimit-reset\\?"\s*:\s*\\?"?(\d{13}|\d{10})(?!\d)""",
        RegexOption.IGNORE_CASE,
    )
    private val RESET_ISO_RE = Regex("""resets?\s+at\s+(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z?)""")
    private val RESET_IN_RE = Regex(""""resets?_(?:in_seconds|in)"\s*:\s*(\d+)""")
}
