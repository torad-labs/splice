// NEW: console review 2026-09-24 — one spelling for "how long ago" in doctor's sentences. The turns
// check printed raw minutes ("last failure: 28710m ago") while `splice status` said "3m ago" / "2d ago"
// for an account switch; both now read the largest whole unit from this one place.
package splice.diagnostics.doctor

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal object DoctorAge {
    /** [elapsedMillis] as `45s ago`, `3m ago`, `5h ago` or `20d ago`; a clock skew reads as `0s ago`. */
    fun ago(elapsedMillis: Long): String = "${span(elapsedMillis)} ago"

    /** Future deadlines round the largest unit up so they never promise an earlier opening. */
    fun until(remainingMillis: Long): String {
        val millis = remainingMillis.coerceAtLeast(0)
        val remaining = millis.milliseconds
        val (unit, suffix) = when {
            remaining.inWholeMinutes == 0L -> 1.seconds to "s"
            remaining.inWholeHours == 0L -> 1.minutes to "m"
            remaining.inWholeDays == 0L -> 1.hours to "h"
            else -> 1.days to "d"
        }
        val unitMillis = unit.inWholeMilliseconds
        val count = millis / unitMillis + if (millis % unitMillis == 0L) 0L else 1L
        return "in $count$suffix"
    }

    private fun span(millis: Long): String {
        val elapsed = millis.coerceAtLeast(0).milliseconds
        return when {
            elapsed.inWholeMinutes == 0L -> "${elapsed.inWholeSeconds}s"
            elapsed.inWholeHours == 0L -> "${elapsed.inWholeMinutes}m"
            elapsed.inWholeDays == 0L -> "${elapsed.inWholeHours}h"
            else -> "${elapsed.inWholeDays}d"
        }
    }
}
