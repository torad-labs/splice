// NEW: Nov 13, 2026: how fast the last login's window fills, so a notice can be about the time left and not only a
// mark. That afternoon every session of a busy machine ran on the one login that could still serve Fable. Its Fable
// week went from 16% to 50% in an hour, and the first mark, 80%, would have come about 35 minutes before the end,
// where it was set to leave 45 at the pace of the day before. A mark is a stand-in for the time a person has to act, and the time
// depends on how many sessions the login carries.
//
// THE PACE IS READ FROM THE DAEMON'S OWN READINGS of that window, one a check, kept in memory for half an hour. A
// restart forgets them, and the pace is known again once the new readings span ten minutes. A window that reads lower
// than its last reading has reset, and what was read of the old one says nothing of the new.
package splice.diagnostics.doctor.accounts

// why: long enough to smooth readings that move in whole points, short enough to follow sessions arriving on a login.
private const val PACE_WINDOW_SECONDS = 1_800L

// why: a reading moves about half a point a minute under a full load, so two readings closer than ten minutes are a
// few points apart at most and say little of the pace.
private const val PACE_SPAN_SECONDS = 600L

// why: twice the 45 minutes the first mark was set to leave: a person away from their phone for an hour still has
// time to call a reset before sessions wait.
private const val SOON_SECONDS = 5_400L

/** The readings of each window a notice was asked about, and the pace they show. One per [LoginNotices]. */
internal class LoginPace {
    private data class Reading(val atSeconds: Long, val percent: Double)

    private val readings = HashMap<String, ArrayDeque<Reading>>()

    /** Takes [window]'s reading at [atSeconds] under [key], which names the login and the window, and answers when
     *  the window is used up at the pace since the oldest reading kept: only when that is within [SOON_SECONDS] and
     *  before the window resets. Null while the readings span under [PACE_SPAN_SECONDS] or the window is not
     *  filling. */
    fun usedUpSoon(key: String, window: LimitWindow, atSeconds: Long): Long? {
        val kept = readings.getOrPut(key) { ArrayDeque() }
        if (kept.lastOrNull()?.let { window.percent < it.percent } == true) kept.clear()
        kept.addLast(Reading(atSeconds, window.percent))
        while (kept.first().atSeconds < atSeconds - PACE_WINDOW_SECONDS) kept.removeFirst()
        val span = atSeconds - kept.first().atSeconds
        val rise = window.percent - kept.first().percent
        if (span < PACE_SPAN_SECONDS || rise <= 0.0) return null
        val usedUpAt = atSeconds + ((USED_UP_PERCENT - window.percent) * span / rise).toLong()
        return usedUpAt.takeIf { it - atSeconds <= SOON_SECONDS && (window.resetsAt == null || it < window.resetsAt) }
    }
}
