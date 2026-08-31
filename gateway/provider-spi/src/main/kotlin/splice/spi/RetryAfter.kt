// PORT-OF: splice/spi/UpstreamClient.kt (RetryRules.retryAfterMs) @ 3879c4c — invariants unchanged: both RFC 7231 forms, strict seconds FIRST, HTTP-date against the WALL clock, past dates clamped to 0, garbage null.
//
// Retry-After -> ms. Its own file (HD-25) because it is the one place in the upstream call that
// holds TWO CLOCK BASES at once: the class it used to live on (UpstreamClient.RetryRules) is
// governed by MonoClock, while an HTTP-date is wall time and can only be converted against
// [WallClock]. Isolating it is what keeps that exception visible instead of buried in a retry class
// whose invariant is the opposite.
//
// WALL: dev/campaigns/proxy-hardening/walls/nf_04_retry_after_date_form.py reads THIS file.
package splice.spi

import splice.core.util.WallClock

/**
 * The `Retry-After` response header in both RFC 7231 forms; garbage → null (the backoff curve
 * decides). Read INSIDE the response's execute block by UpstreamClient.attemptRequest, and consumed
 * three ways: as the backoff FLOOR (`minDelayMs`), as the absurd-pushback give-up threshold, and as
 * the 429 cooldown horizon.
 */
internal class RetryAfter {
    /** Strict seconds FIRST so nothing on the pre-NF-04 path changes; the HTTP-date form (NF-04:
     *  Cloudflare and gateway fronts emit it) is the FALLBACK.
     *
     *  THE ORDER IS THE SPEC, and the elvis below is a deliberate reading of it, not a shortening:
     *  a negative seconds value ("-5") used to return null WITHOUT attempting the date parse, and
     *  now falls through to [httpDateMs], which cannot match it and returns null too. Identical
     *  observable result — no RFC 1123 date is also a Long, so a WELL-FORMED value can never reach
     *  the second parser, and the only strings that do are ones both parsers reject. */
    fun retryAfterMs(header: String?, nowEpochMs: WallClock = WallClock(System::currentTimeMillis)): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return secondsFormMs(value) ?: httpDateMs(value, nowEpochMs)
    }

    /** The COMMON path: `Retry-After: 30`. A negative delay is not a delay — null, and the curve
     *  decides. SATURATING above Long.MAX/1000 (DR-47): the naive ×1000 wrapped a hostile or
     *  broken header to a NEGATIVE delay, which read as "tiny pushback" everywhere — floor,
     *  give-up threshold, and cooldown all disabled by one absurd number. Saturated MAX_VALUE
     *  trips the give-up comparison instead, which is what an absurd pushback should do. */
    private fun secondsFormMs(value: String): Long? =
        value.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.let { seconds -> if (seconds > Long.MAX_VALUE / MS_PER_S) Long.MAX_VALUE else seconds * MS_PER_S }

    /** The NF-04 fallback: `Retry-After: Wed, 21 Oct 2026 07:28:00 GMT`. Converted against the WALL
     *  clock deliberately — an HTTP-date is wall time and MonoClock has no epoch — clamping past
     *  dates to 0. A skewed clock can only inflate the delta into NF-01's 120s cooldown ceiling /
     *  the 15s give-up on a RETRYABLE status (429/408/5xx — UP-001: statusPlan only arms the shared
     *  cooldown for those); a non-retryable status's inflated delta never touches the cooldown at
     *  all, so it can never wedge the head for OTHER turns. */
    private fun httpDateMs(value: String, nowEpochMs: WallClock): Long? = try {
        val at = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        (at.toInstant().toEpochMilli() - nowEpochMs()).coerceAtLeast(0L)
    } catch (ignored: java.time.format.DateTimeParseException) {
        null // not seconds, not an HTTP-date: garbage stays null BY CONTRACT — the curve decides
    }
}

// One definition of the second, read here (seconds -> ms) and by RateLimitCooldown's fail-fast
// message (ms -> whole seconds remaining).
internal const val MS_PER_S = 1000L
