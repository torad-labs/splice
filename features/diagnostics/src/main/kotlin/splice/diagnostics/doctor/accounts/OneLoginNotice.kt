// NEW: Nov 13, 2026: a command with one login is told of as well. LoginNotices read a command by the models its pool
// saw turns ask for, and a command with one login has no pool, so it never had a notice. That day a command's only
// login passed both marks of its week hours before it ran out, was refused until four days later, two sessions
// waited, and nothing was said. A command with one login is always on its last one.
//
// IT IS READ BY ITS PLAN'S OWN WINDOWS, the fullest still running of the 5-hour limit, the weekly limit and a model's
// week: no pool says which model its turns ask for. A login whose provider reports no window says nothing, so an
// API key or a local runtime never does.
//
// OUT IS A WINDOW THAT READS USED UP, or a model its provider refused until a time still ahead. No pool holds a single
// login, so nothing else says it is out, and the window's own reset is when it comes back.
package splice.diagnostics.doctor.accounts

import splice.accounts.pool.HeadAccountView
import splice.upstream.credentials.AccountResetText
import kotlin.math.roundToInt

// why: a provider reports a window that is spent as 100.
internal const val USED_UP_PERCENT = 100.0

/** What is said of [head]'s one [login], which a person knows as [name], at [atSeconds]. */
internal class OneLoginNotice(
    private val head: String,
    private val login: HeadAccountView,
    private val name: String,
    private val atSeconds: Long,
) {
    /** The login's fullest window still running; null where its provider reports none. */
    private val fullest: LimitWindow? = login.quota.let { quota ->
        val weeks = quota.sevenDayModels.filter { it.runsAt(atSeconds) }
            .map { LimitWindow("${it.model} week", it.usedPercent, it.resetsAt) }
        val plan = listOfNotNull(
            quota.sevenDay.usedPercent?.let { LimitWindow("weekly limit", it, quota.sevenDay.resetEpochSeconds) },
            quota.fiveHour.usedPercent?.let { LimitWindow("5-hour limit", it, quota.fiveHour.resetEpochSeconds) },
        )
        val refused = quota.holds.models.map { LimitWindow("${it.model} limit", USED_UP_PERCENT, it.resetEpochSeconds) }
        (refused + weeks + plan).filter { it.resetsAt == null || it.resetsAt > atSeconds }.maxByOrNull { it.percent }
    }

    /** That the login is out, or near a limit past a mark; null below the first mark and with no window. */
    fun notice(): LoginNotice? = fullest?.let { if (it.percent >= USED_UP_PERCENT) out(it) else near(it) }

    /** Below the first mark: that the login's fullest window fills fast enough to be used up soon ([LoginPace]). */
    fun soon(pace: LoginPace): LoginNotice? = fullest?.takeIf { it.percent < NEAR_MARK }?.let { window ->
        val key = "$head|login|${window.words}"
        pace.usedUpSoon(key, window, atSeconds)?.let { usedUpAt ->
            LoginNotice(
                head,
                "$key|$LOGIN_NOTICE_SOON",
                "$head has one login, and $name has used ${window.percent.roundToInt()}% of its ${window.words}. " +
                    "At the pace it is being used it runs out around ${AccountResetText.forPerson(usedUpAt)}. " +
                    "When it does, sessions on $head wait.",
            )
        }
    }

    /** That the login can serve again; null while a window reads used up. */
    fun back(): LoginNotice? = LoginNotice(
        head,
        "$head|login|$LOGIN_NOTICE_BACK",
        "$head's login can serve again: $name. Sessions that waited for it go on.",
    ).takeIf { (fullest?.percent ?: 0.0) < USED_UP_PERCENT }

    private fun out(window: LimitWindow): LoginNotice {
        val back = window.resetsAt?.let { " It comes back ${AccountResetText.forPerson(it)}." }.orEmpty()
        return LoginNotice(
            head,
            "$head|login|$LOGIN_NOTICE_NONE",
            "$head has one login, and $name has used up its ${window.words}, so sessions on $head are waiting.$back",
        )
    }

    private fun near(window: LimitWindow): LoginNotice? {
        val mark = listOf(NEAR_MARK, LAST_MARK).lastOrNull { window.percent >= it } ?: return null
        val resets = window.resetsAt?.let { ", which resets ${AccountResetText.forPerson(it)}" }.orEmpty()
        return LoginNotice(
            head,
            "$head|login|${window.words}|$mark",
            "$head has one login, and $name has used ${window.percent.roundToInt()}% of its ${window.words}$resets. " +
                "When it runs out, sessions on $head wait.",
        )
    }
}
