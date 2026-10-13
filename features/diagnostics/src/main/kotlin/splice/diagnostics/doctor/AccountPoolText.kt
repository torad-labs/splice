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

    /** `on Work (1 of 2 open) · pinned to Work · 5h 12% · 7d 40%; switched from Max to Work 3m ago: 7d window exhausted`.
     *  [names] are the logins' Accounts names by pool label; a label with none is printed as it is. */
    public fun summary(view: HeadAccountPoolView, names: Map<String, String> = emptyMap()): String {
        val safe = safeView(view)
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
        val parts = listOfNotNull(head, lastLeft(safe), pin, missing, selected?.let(::windows))
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
        if (!exhausted) return DoctorCheck(headKey, openStatus(safe), detail)
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
            if (safe.accounts.all { it.label.startsWith("native:") }) {
                "Wait for the earliest reset above, or run another of the native login places and use /login."
            } else {
                "splice login $headKey --label <name>"
            },
        )
    }

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
