// NEW: console review 2026-09-24 — one spelling for "how long ago" in doctor's sentences. The turns
// check printed raw minutes ("last failure: 28710m ago") while `splice status` said "3m ago" / "2d ago"
// for an account switch; both now read the largest whole unit from this one place.
package splice.diagnostics.doctor

import kotlin.time.Duration.Companion.milliseconds

internal object DoctorAge {
    /** [elapsedMillis] as `45s ago`, `3m ago`, `5h ago` or `20d ago`; a clock skew reads as `0s ago`. */
    fun ago(elapsedMillis: Long): String = "${span(elapsedMillis)} ago"

    /** A future deadline uses the same largest whole unit as a past observation. */
    fun until(remainingMillis: Long): String = "in ${span(remainingMillis)}"

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
