// NEW: Oct 10, 2026 — a vendor that bills by the hour of the day. DeepSeek bills twice its off-peak card
// from 01:00 to 04:00 and from 06:00 to 10:00 UTC, Monday to Friday (api-docs.deepseek.com/quick_start/pricing),
// so a turn is priced at the card for the hour it ran in (Marlin's call). The hours are data in
// published-rates.tsv beside the card; this type only says whether an instant falls inside them.
package splice.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/** The hours a vendor bills [factor] times its card: [hoursUtc] (each end exclusive) in UTC, on Monday to
 *  Friday only when [weekdaysOnly], else every day. */
public data class PeakHours(val factor: Double, val hoursUtc: List<IntRange>, val weekdaysOnly: Boolean) {
    /** Whether the instant [atMs] falls inside these hours. */
    public fun covers(atMs: Long): Boolean {
        val at = Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC)
        if (weekdaysOnly && at.dayOfWeek >= DayOfWeek.SATURDAY) return false
        return hoursUtc.any { at.hour in it }
    }
}
