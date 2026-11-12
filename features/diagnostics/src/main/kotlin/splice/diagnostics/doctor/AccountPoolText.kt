// NEW: v0.4.0 FEATURES.md §11 — one wording for the account a head is on, shared by `splice status`
// and `splice doctor`: the selected label, how many accounts are open, the selected account's plan
// windows, and the last switch with its reason and age. Labels are operator-chosen and regex-safe,
// reasons are splice's own text; nothing else from the pool is rendered.
package splice.diagnostics.doctor

import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.core.util.WallClock
import splice.upstream.credentials.AccountLabelPolicy
import splice.upstream.credentials.AccountResetText
import splice.upstream.credentials.AccountSwitchReason
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

public class AccountPoolText(private val now: WallClock = WallClock { System.currentTimeMillis() }) {
    // A model's name on a usage row is the provider's text and goes to a terminal: letters, digits, spaces, dots and
    // hyphens only.
    private val plainModel = Regex("[A-Za-z0-9][A-Za-z0-9 .-]{0,39}")

    /** `on Work (1 of 2 open) · pinned to Work · 5h 12% · 7d 40%; switched from Max to Work 3m ago: 7d window exhausted`.
     *  [names] are the logins' Accounts names by pool label; a label with none is printed as it is. A command nobody
     *  has signed in to Claude Code for reads `not signed in to Claude Code yet`, in status and doctor alike. */
    public fun summary(view: HeadAccountPoolView, names: Map<String, String> = emptyMap()): String {
        val safe = safeView(view)
        return if (neverSignedIn(safe)) NOT_SIGNED_IN else standing(safe, names)
    }

    /** The login a command is on, how many are open, and what else its pool has to say. */
    private fun standing(safe: HeadAccountPoolView, names: Map<String, String>): String {
        val selected = if (safe.selectionUnknown) null else safe.selectedAccount()
        val open = safe.accounts.count { it.available && it.credential.present }
        val choice = selected?.label?.let { shown(it, names) } ?: "no account"
        val selection = if (safe.selectionUnknown) "selection unknown" else "on $choice"
        val head = "$selection ($open of ${safe.accounts.size} open)"
        val pin = safe.pinnedLabel?.let { "pinned to ${shown(it, names)}" }
        val missing = "primary credential missing".takeIf { safe.accounts.any { it.primary && !it.credential.present } }
        val switch = safe.lastSwitch?.let {
            "; switched from ${shown(it.from, names)} to ${shown(it.to, names)} ${ago(it.atEpochMillis)}: ${it.reason}"
        }
        val parts = listOfNotNull(head, lastLeft(safe), modelWeeks(safe), pin, missing, selected?.let(::windows))
        return parts.joinToString(" · ") + switch.orEmpty()
    }

    /** Missing primary requires unlabeled login even while a backup is open; exhaustion names a reset. */
    internal fun check(
        headKey: String,
        view: HeadAccountPoolView,
        names: Map<String, String> = emptyMap(),
    ): DoctorCheck {
        val safe = safeView(view)
        val detail = summary(safe, names)
        if (safe.accounts.any { it.primary && !it.credential.present }) {
            return DoctorCheck(headKey, CheckStatus.WARN, detail, "splice login $headKey", fixKind = FixKind.COMMAND)
        }
        val exhausted = safe.accounts.isNotEmpty() && safe.accounts.none { it.available && it.credential.present }
        return when {
            !exhausted -> DoctorCheck(headKey, openStatus(safe), detail)
            neverSignedIn(safe) -> DoctorCheck(headKey, CheckStatus.WARN, detail, "Run $headKey and use /login.")
            else -> out(headKey, safe, detail)
        }
    }

    /** Every login the command has is held or spent: when the first comes back, and what to do meanwhile. */
    private fun out(headKey: String, safe: HeadAccountPoolView, detail: String): DoctorCheck {
        val atMillis = now()
        val reset = safe.accounts.mapNotNull { safe.blockedUntilEpochSecondsByLabel[it.label] }
            .filter { it.seconds > atMillis.milliseconds }.minOrNull()
        val at = reset?.let {
            val remaining = (it.seconds - atMillis.milliseconds).inWholeMilliseconds
            "earliest reset ${AccountResetText.forPerson(it)}, ${DoctorAge.until(remaining)}"
        } ?: "no reset time reported"
        return DoctorCheck(
            headKey,
            CheckStatus.WARN,
            "every account is out ($at): $detail",
            if (safe.accounts.all { it.label.startsWith(NATIVE_LABEL) }) {
                "Wait for the earliest reset above, or sign Claude Code in to another account with /login."
            } else {
                "splice login $headKey --label <name>"
            },
        )
    }

    /** A command that runs on Claude Code's own logins with none on this machine: nobody has signed in yet, which is
     *  one thing to do and no limit to wait out. Until Nov 11, 2026 a new install read "every account is out (no reset
     *  time reported)" with "Wait for the earliest reset above", beside one "Sign in again" warning per login place. */
    public fun neverSignedIn(view: HeadAccountPoolView): Boolean = view.accounts.isNotEmpty() &&
        view.accounts.all { it.label.startsWith(NATIVE_LABEL) && !it.credential.present }

    /** Rendering also accepts directly constructed views, so decoding is not the only label gate. */
    private fun safeView(view: HeadAccountPoolView): HeadAccountPoolView {
        val accounts = view.accounts.filter { AccountLabelPolicy.isSelector(it.label) }
        val missingSelection = view.selectedLabel != null && accounts.none { it.label == view.selectedLabel }
        val droppedSelected = view.accounts.any { it.selected && !AccountLabelPolicy.isSelector(it.label) }
        return view.copy(
            selectedLabel = view.selectedLabel?.takeIf(AccountLabelPolicy::isSelector),
            pinnedLabel = view.pinnedLabel?.takeIf(AccountLabelPolicy::isSelector),
            accounts = accounts,
            lastSwitch = view.lastSwitch?.takeIf {
                AccountLabelPolicy.isSelector(it.from) && AccountLabelPolicy.isSelector(it.to) &&
                    AccountSwitchReason.isSafe(it.reason)
            },
            selectionUnknown = view.selectionUnknown || missingSelection || droppedSelected,
        )
    }

    /** Each model whose own week some logins have used up, and on how many: `Fable used up on 6 of 8 logins`, and once
     *  every login has, when the first comes back. `(7 of 9 open)` counts a login open while any model has room on
     *  it, so on Nov 12, 2026 it read that way for three hours while every login had used up the model all eleven
     *  sessions ran, and nothing in status said so. Null where no login reads a model's week used up. */
    private fun modelWeeks(safe: HeadAccountPoolView): String? {
        val atSeconds = now().milliseconds.inWholeSeconds
        val logins = safe.accounts.filter { it.credential.present }
        val usedUp = logins.flatMap { login -> usedUp(login, atSeconds) }.groupBy({ it.first }, { it.second })
        return usedUp.entries.joinToString(", ") { (model, resets) ->
            val every = resets.size == logins.size
            val back = resets.filterNotNull().filter { it > atSeconds }.minOrNull()?.takeIf { every }
                ?.let { ", first back ${AccountResetText.forPerson(it)}" }.orEmpty()
            val where = if (every) "every login" else "${resets.size} of ${logins.size} logins"
            "$model used up on $where$back"
        }.takeIf { it.isNotEmpty() }
    }

    /** The models [login] reads used up, each with when its week resets: its own reset, else the login's week's. The
     *  name is the provider's, so only a plain one is printed. */
    private fun usedUp(login: HeadAccountView, atSeconds: Long): List<Pair<String, Long?>> =
        login.quota.sevenDayModels
            .filter { it.runsAt(atSeconds) && it.usedUp && plainModel.matches(it.model) }
            .map { it.model to (it.resetsAt ?: login.quota.sevenDay.resetEpochSeconds) }

    /** A pool down to its last usable login says so, with the earliest reset among the held ones (splice-lead, Oct 13:
     *  claude-splice ran on one live login most of the day, and nothing said the next limit had no fallback). Null for
     *  a pool of one, which never had a fallback, and for a pool with two or more open. */
    private fun lastLeft(safe: HeadAccountPoolView): String? {
        val open = safe.accounts.filter { it.available && it.credential.present }
        if (safe.accounts.size < 2 || open.size != 1) return null
        val atMillis = now()
        val reset = safe.accounts.filter { it !in open }.mapNotNull { safe.blockedUntilEpochSecondsByLabel[it.label] }
            .filter { it.seconds > atMillis.milliseconds }.minOrNull()
        val until = reset?.let { " until ${AccountResetText.forPerson(it)}" }.orEmpty()
        return "one login left; the next plan limit has no fallback$until"
    }

    /** A pool with a login open is fine, and down to its last one it is worth knowing: INFO, never a warning. */
    private fun openStatus(safe: HeadAccountPoolView): CheckStatus =
        if (lastLeft(safe) == null) CheckStatus.OK else CheckStatus.INFO

    /** A login by its Accounts name when it has one, else by its label. */
    private fun shown(label: String, names: Map<String, String>): String = names[label] ?: name(label)

    private fun name(label: String): String = ClaudeLoginPlaceId.entries
        .singleOrNull { label == "native:${it.wire}" }?.let { "${it.command}'s login" } ?: label

    private fun windows(a: HeadAccountView): String? = listOfNotNull(
        a.quota.fiveHour.usedPercent?.let { "5h ${it.roundToInt()}%" },
        a.quota.sevenDay.usedPercent?.let { "7d ${it.roundToInt()}%" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    private fun ago(atMillis: Long): String = DoctorAge.ago(now() - atMillis)
}

// why: a login Claude Code itself holds is labelled by its place, `native:<place>`; an added login is `account-N`.
private const val NATIVE_LABEL = "native:"

private const val NOT_SIGNED_IN = "not signed in to Claude Code yet"
