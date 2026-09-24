// NEW: console review 2026-09-24 — one spelling for "how long ago" in doctor's sentences. The turns
// check printed raw minutes ("last failure: 28710m ago") while `splice status` said "3m ago" / "2d ago"
// for an account switch; both now read the largest whole unit from this one place.
package splice.diagnostics.doctor

import kotlin.time.Duration.Companion.milliseconds

internal object DoctorAge {
    /** [elapsedMillis] as `45s ago`, `3m ago`, `5h ago` or `20d ago`; a clock skew reads as `0s ago`. */
    fun ago(elapsedMillis: Long): String {
        val elapsed = elapsedMillis.coerceAtLeast(0).milliseconds
        return when {
            elapsed.inWholeMinutes == 0L -> "${elapsed.inWholeSeconds}s ago"
            elapsed.inWholeHours == 0L -> "${elapsed.inWholeMinutes}m ago"
            elapsed.inWholeDays == 0L -> "${elapsed.inWholeHours}h ago"
            else -> "${elapsed.inWholeDays}d ago"
        }
    }
}
