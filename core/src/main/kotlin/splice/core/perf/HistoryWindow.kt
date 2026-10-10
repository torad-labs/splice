// NEW: Oct 10, 2026 — ONE window for everything splice keeps about finished turns: the hourly
// totals and the request records. Replaces EconomicsRetention.kt, whose job it inherits (one
// declaration the trimming store and the reporting payload both read, in the lowest module that
// reaches them), and the operator-facing half of perfArchiveRetentionDays.
//
// WHY ONE SETTING AND NOT TWO. The two were never two questions a person has: "how far back can I
// read my spending" and "how far back can I read my requests" are the same question about the same
// days, asked of two files. Two knobs meant one could silently be shorter than the other, which is
// how a month-wide view ends up drawn over eight days of buckets (the figure Marlin stopped on
// Oct 10). The console asks it once, on Settings > Your data.
//
// WHY THE WINDOW IS A TYPE AND NOT AN Int. `forever` is a value a person may choose, and an Int
// cannot hold it: 0 already means "keep nothing", and a sentinel like -1 or Int.MAX_VALUE is a
// number every reader has to remember the meaning of. A null [days] says it once, here, and every
// reader gets it from [cutoffMs] returning null — "no cutoff" rather than "a cutoff in 5.8 million
// years", which is the same arithmetic with a lie in it.
//
// WHY A DAY IS 24 HOURS HERE, EXCEPT FOR ZERO. For a window of days, 24 hours each makes the cutoff
// exact and the same in every zone, and no person can read the difference a DST hour makes to a
// 35-day window. Zero is the one case a person reads as a calendar day: "keep nothing" is a choice
// the console offers, and a daily budget still has to know what today cost (Marlin, Oct 10, 2026),
// so zero keeps the day that is running where the daemon runs and drops it at that midnight. That is
// the same boundary the budget day is drawn on (BudgetLedger).
package splice.core.perf

import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.days

/** 35 days: what a FRESH install keeps (written into its starter splice.toml, so the person can
 *  read and change the number that governs their disk). A longest month plus the days it is read
 *  over always fits, so the month view every plan is billed by is there; it also holds one full
 *  weekly quota window with room to spare, so the week-to-date figure stays exact across a reset.
 *
 *  An install that PREDATES this setting does not take it: it keeps the records window it already
 *  had (perfArchiveRetentionDays, 90), because an upgrade never shortens history on its own
 *  (Marlin, Oct 10, 2026). SpliceConfig.historyWindow is where those two meet. */
public const val HISTORY_DEFAULT_DAYS: Int = 35

/** The literal word that keeps everything, in splice.toml and on the wire. A word rather than a
 *  number because every number is a window, and the one choice that is not a window should not have
 *  to be spelled as one. */
internal const val HISTORY_FOREVER: String = "forever"

/** How far back splice keeps a head's history: its hourly totals and its request records.
 *
 *  [days] is whole days, or null for [HISTORY_FOREVER]: keep everything. Zero keeps the running day
 *  only, so a daily budget can still say what today cost, and drops it at midnight where the daemon
 *  runs. Nothing is kept beyond it: no finished day, no earlier hour. */
public data class HistoryWindow(
    val days: Int?,
    /** Where a day begins and ends for a window of zero: the operator's own midnight, never UTC's. */
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Keeps everything: no hour and no archived generation is ever old enough to delete. */
    public val forever: Boolean get() = days == null

    /** Keeps no finished day: today, and nothing once today ends. */
    public val nothing: Boolean get() = days == 0

    /** The oldest millisecond this window keeps at [nowMs], or null when it keeps everything.
     *  A reader trims what is older; a null answer means it trims nothing at all. */
    public fun cutoffMs(nowMs: Long): Long? = when (days) {
        null -> null
        0 -> Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        else -> nowMs - days * 1.days.inWholeMilliseconds
    }

    /** The same window in the unit the console reads it in, or null when it keeps everything. */
    public val hours: Long? get() = days?.let { it * 1.days.inWholeHours }

    /** How it is written in splice.toml and answered on the wire. */
    public val text: String get() = days?.toString() ?: HISTORY_FOREVER
}

/** The words the history window reads, any case, around whitespace: whole days from zero up, or
 *  [HISTORY_FOREVER], read in [zone] so that one install has ONE answer to where a day begins: a
 *  window of zero cuts at midnight, and a reader that parsed the word without saying which midnight
 *  would cut five hours from where the same page counted (caught by HistoryRoutesTest, Oct 10).
 *  Anything else is no value — every layer refuses it by name and the window
 *  keeps what it had, the way a bool knob's refused word does ([BoolKnobWords]'s shape, V4-286).
 *  A refusal matters more here than for most knobs: the knob governs deletion, and a typo that read
 *  as a short window would delete history nobody asked it to. */
public object HistoryWindowWords {
    /** The window a word names, or null when the word names none. */
    public fun of(raw: String, zone: ZoneId = ZoneId.systemDefault()): HistoryWindow? {
        val word = raw.trim()
        if (word.equals(HISTORY_FOREVER, ignoreCase = true)) return HistoryWindow(null, zone)
        val days = word.toIntOrNull() ?: return null
        return if (days >= 0) HistoryWindow(days, zone) else null
    }
}
