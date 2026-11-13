// NEW: what the daemon tells the operator about a command's logins before they open the console (Nov 12, 2026).
// That morning every Claude login ran out of Fable one after another and eleven sessions waited three hours. The
// operator had resets to spend, and nothing told him the last login was nearly out (Marcos: "just let me know when
// we're on the last account close to hit limit and I'll call a reset").
//
// ONE NOTICE PER COMMAND AND MODEL, read from the roster /api/accounts serves, so it never says what Accounts and
// `splice status` do not show. A login can serve when the pool would send it a turn of that model: it is open, the
// pool holds no refusal of that model on it, and that model's week does not read used up. A command with one login
// has no pool that knows its models: OneLoginNotice reads it by its plan's own windows.
//
// EVERY MODEL THE COMMAND'S TURNS ASKED FOR IN THE LAST HOUR IS READ, not only the one most turns asked for. The
// first form read that one alone, and on the afternoon it shipped the Claude command's was Haiku: sessions hand
// their reading to helpers, and the helpers' turns outnumbered their own. Every login serves Haiku, so the command
// would have gone down to its last Fable login and through it with nothing said. Two models that share a week
// ("Fable") share a notice.
//
// A COMMAND IS READ ONLY ONCE A MODEL IS KNOWN. The pool learns the models from the turns it selects for, so for a
// moment after a start it knows none and the command has no notice.
package splice.diagnostics.doctor.accounts

import splice.accounts.pool.HeadAccountView
import splice.core.usage.ModelQuota
import splice.core.util.WallClock
import splice.diagnostics.doctor.AccountPoolsRead
import splice.upstream.credentials.AccountResetText
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/** How the key of the notice that no login can serve ends, and the key of the notice that one can again. */
public const val LOGIN_NOTICE_NONE: String = "none"
public const val LOGIN_NOTICE_BACK: String = "back"

// why: below any reading a provider gives, so a login with a reading of the week is the one an account is read by.
private const val NO_READING = -1.0

// why: leaves time to act: at the pace of Nov 12, 2026, eleven sessions on one login, about 45 minutes.
internal const val NEAR_MARK = 80

// why: says it again for someone who missed the first, about ten minutes before the end at that pace.
internal const val LAST_MARK = 95

/** One command's notice. [key] names the command and what the notice says of it, and changes when that changes. */
public data class LoginNotice(public val head: String, public val key: String, public val text: String)

public class LoginNotices(private val now: WallClock = WallClock { System.currentTimeMillis() }) {
    private val roster = AccountRosterProjection()

    /** The notice each command of [accountsJson], the daemon's own /api/accounts body, calls for now: none for a
     *  command with two or more logins that can serve, and none for a body this build cannot read. */
    public fun of(accountsJson: String): List<LoginNotice> = read(accountsJson)?.let { read ->
        pooled(read) { asked, blockedUntil, names -> asked.notice(blockedUntil, names) } +
            alone(read).mapNotNull(OneLoginNotice::notice)
    }.orEmpty()

    /** For each command and model [accountsJson] shows a login able to serve now, the notice that says so. It is for a
     *  command that was told it had none (Nov 12, 2026: a login was reset while sessions waited, and nothing said
     *  the sessions had gone on, so the person logged in again by hand). Its key is the [LOGIN_NOTICE_NONE] notice's
     *  with [LOGIN_NOTICE_BACK] in that place. */
    public fun back(accountsJson: String): List<LoginNotice> = read(accountsJson)?.let { read ->
        pooled(read) { asked, _, names -> asked.back(names) } + alone(read).mapNotNull(OneLoginNotice::back)
    }.orEmpty()

    private fun read(accountsJson: String): AccountPoolsRead.Read? =
        roster.read(accountsJson) as? AccountPoolsRead.Read

    /** The commands with one login, each read by its plan's windows. */
    private fun alone(read: AccountPoolsRead.Read): List<OneLoginNotice> {
        val atSeconds = now().milliseconds.inWholeSeconds
        return read.pools.mapNotNull { (head, view) ->
            view.accounts.singleOrNull { it.credential.present }?.let { login ->
                OneLoginNotice(head, login, read.accountNames[head].orEmpty()[login.label] ?: login.label, atSeconds)
            }
        }
    }

    private fun pooled(read: AccountPoolsRead.Read, said: NoticeOf): List<LoginNotice> {
        val atSeconds = now().milliseconds.inWholeSeconds
        return read.pools.flatMap { (head, view) ->
            val logins = view.accounts.filter { it.credential.present }
            (view.next.asked + listOfNotNull(view.usualModel)).distinct().mapNotNull { model ->
                val asked = AskedOfLogins(head, model, atSeconds, logins, read.accountRefs[head].orEmpty())
                said.of(asked, view.blockedUntilEpochSecondsByLabel, read.accountNames[head].orEmpty())
            }
        }.distinctBy { it.key }
    }
}

/** What is said of one command's logins for one model, given the pool's hold times and the logins' names. */
private fun interface NoticeOf {
    fun of(asked: AskedOfLogins, blockedUntil: Map<String, Long>, names: Map<String, String>): LoginNotice?
}

/** A window of the last login, by the words a person knows it by. */
internal data class LimitWindow(val words: String, val percent: Double, val resetsAt: Long?)

/** What a command's turns ask of its [logins] at one moment: turns of [model]. */
private class AskedOfLogins(
    private val head: String,
    private val model: String,
    private val atSeconds: Long,
    private val logins: List<HeadAccountView>,
    accountOf: Map<String, String>,
) {
    /** The logins by the account each is a login of. A LIMIT IS AN ACCOUNT'S, so the notices count accounts: on Nov
     *  12, 2026 Claude Code's own login was signed in to the account of a saved login, and the command read as
     *  having two logins that could serve when it had one account. A login that proved no account stands alone. */
    private val accounts: List<List<HeadAccountView>> =
        logins.groupBy { accountOf[it.label] ?: "login:${it.label}" }.values.toList()

    /** The model as a person names it: its week's name on a login that reports one ("Fable"), else its id. */
    private val modelWords: String = logins.firstNotNullOfOrNull { login -> week(login)?.model } ?: model

    /** The model's own week on [login], where its provider reports one that is still running. */
    private fun week(login: HeadAccountView): ModelQuota? =
        login.quota.sevenDayModels.firstOrNull { it.weekOf(model) && it.runsAt(atSeconds) }

    /** What the logins call for saying: nothing while two or more accounts can serve, or for a command with one. */
    fun notice(blockedUntil: Map<String, Long>, names: Map<String, String>): LoginNotice? {
        val open = accounts.filter(::serves)
        val last = open.singleOrNull()?.let(::read)
        return when {
            accounts.size < 2 || open.size > 1 -> null
            last == null -> none(blockedUntil)
            else -> near(last, names[last.label] ?: last.label)
        }
    }

    /** An account can serve when a login of it is open and no login of it shows the model spent. */
    private fun serves(account: List<HeadAccountView>): Boolean =
        account.any { it.available } && account.all(::servedBy)

    /** The login an account is read by: the one whose reading of the model's week is fullest, else its first. */
    private fun read(account: List<HeadAccountView>): HeadAccountView =
        account.maxByOrNull { week(it)?.usedPercent ?: NO_READING } ?: account.first()

    /** That a login can serve again, named by the first that can; null while none can. */
    fun back(names: Map<String, String>): LoginNotice? = accounts.firstOrNull(::serves)?.let(::read)?.let { login ->
        LoginNotice(
            head,
            "$head|$modelWords|$LOGIN_NOTICE_BACK",
            "$head has a login that can serve $modelWords again: ${names[login.label] ?: login.label}. " +
                "Sessions that waited for it go on.",
        )
    }

    /** Whether [login] shows the model open: no hold of it on the login, and its week not used up. A login that is
     *  closed for another reason (a credential to renew) says nothing of its account's limits. */
    private fun servedBy(login: HeadAccountView): Boolean {
        val held = login.quota.holds.models.any { it.model == model && it.resetEpochSeconds > atSeconds }
        return !held && week(login)?.usedUp != true
    }

    /** [login] is the last that can serve: a notice once its fullest running window has passed a mark. */
    private fun near(login: HeadAccountView, name: String): LoginNotice? {
        val quota = login.quota
        val windows = listOfNotNull(
            week(login)?.let { LimitWindow("${it.model} week", it.usedPercent, it.resetsAt) },
            quota.sevenDay.usedPercent?.let { LimitWindow("weekly limit", it, quota.sevenDay.resetEpochSeconds) },
            quota.fiveHour.usedPercent?.let { LimitWindow("5-hour limit", it, quota.fiveHour.resetEpochSeconds) },
        ).filter { it.resetsAt == null || it.resetsAt > atSeconds }
        val fullest = windows.maxByOrNull { it.percent } ?: return null
        val mark = listOf(NEAR_MARK, LAST_MARK).lastOrNull { fullest.percent >= it } ?: return null
        val resets = fullest.resetsAt?.let { ", which resets ${AccountResetText.forPerson(it)}" }.orEmpty()
        return LoginNotice(
            head,
            "$head|$modelWords|${login.label}|${fullest.words}|$mark",
            "$head is on its last login that can serve $modelWords: $name has used " +
                "${fullest.percent.roundToInt()}% of its ${fullest.words}$resets. When it runs out, sessions asking " +
                "for $modelWords wait.",
        )
    }

    /** No login can serve: said with when the first comes back, by the pool's own hold times and the model's weeks. */
    private fun none(blockedUntil: Map<String, Long>): LoginNotice {
        val back = logins.mapNotNull { login -> backAt(login, blockedUntil[login.label]) }.minOrNull()
        val first = back?.let { " The first comes back ${AccountResetText.forPerson(it)}." }.orEmpty()
        return LoginNotice(
            head,
            "$head|$modelWords|$LOGIN_NOTICE_NONE",
            "$head has no login that can serve $modelWords, so sessions asking for it are waiting.$first",
        )
    }

    /** When [login] serves again: once everything that keeps it out has passed. Null when nothing says. */
    private fun backAt(login: HeadAccountView, blockedUntil: Long?): Long? {
        val held = login.quota.holds.models.filter { it.model == model }.maxOfOrNull { it.resetEpochSeconds }
        val week = week(login)?.takeIf { it.usedUp }?.resetsAt
        return listOfNotNull(held, week, blockedUntil).filter { it > atSeconds }.maxOrNull()
    }
}
