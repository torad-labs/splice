// NEW: v0.4.0 FEATURES.md §11 — one wording for the account a head is on, shared by `splice status`
// and `splice doctor`: the selected label, how many accounts are open, the selected account's plan
// windows, and the last switch with its reason and age. Labels are operator-chosen and regex-safe,
// reasons are splice's own text; nothing else from the pool is rendered.
package splice.diagnostics.doctor

import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.core.util.WallClock
import splice.upstream.credentials.AccountLabelPolicy
import java.time.Instant
import kotlin.math.roundToInt

public class AccountPoolText(private val now: WallClock = WallClock { System.currentTimeMillis() }) {

    /** `on work (1 of 2 open) · 5h 12% · 7d 40%; switched primary -> work 3m ago: 7d window exhausted` */
    public fun summary(view: HeadAccountPoolView): String {
        val safe = safeView(view)
        val selected = if (safe.selectionUnknown) null else safe.selectedAccount()
        val open = safe.accounts.count { it.available && it.credentialPresent }
        val selection = if (safe.selectionUnknown) "selection unknown" else "on ${selected?.label ?: "no account"}"
        val head = "$selection ($open of ${safe.accounts.size} open)"
        val missing = if (safe.accounts.any { it.primary && !it.credentialPresent }) {
            "primary credential missing"
        } else {
            null
        }
        val switch = safe.lastSwitch?.let { "; switched ${it.from} -> ${it.to} ${ago(it.atEpochMillis)}: ${it.reason}" }
        return listOfNotNull(head, missing, selected?.let(::windows)).joinToString(" · ") + switch.orEmpty()
    }

    /** Missing primary requires unlabeled login even while a backup is open; exhaustion names a reset. */
    internal fun check(headKey: String, view: HeadAccountPoolView): DoctorCheck {
        val safe = safeView(view)
        val detail = summary(safe)
        if (safe.accounts.any { it.primary && !it.credentialPresent }) {
            return DoctorCheck(headKey, CheckStatus.WARN, detail, "splice login $headKey")
        }
        val exhausted = safe.accounts.isNotEmpty() && safe.accounts.none { it.available && it.credentialPresent }
        if (!exhausted) return DoctorCheck(headKey, CheckStatus.OK, detail)
        val reset = safe.accounts.mapNotNull(::earliestReset).minOrNull()
        val at = reset?.let { "earliest reset ${Instant.ofEpochSecond(it)}" } ?: "no reset time reported"
        return DoctorCheck(
            headKey,
            CheckStatus.WARN,
            "every account is out ($at) — $detail",
            "splice login $headKey --label <name>",
        )
    }

    /** Rendering also accepts directly constructed views, so decoding is not the only label gate. */
    private fun safeView(view: HeadAccountPoolView): HeadAccountPoolView {
        val accounts = view.accounts.filter { AccountLabelPolicy.isSafe(it.label) }
        val missingSelection = view.selectedLabel != null && accounts.none { it.label == view.selectedLabel }
        val droppedSelected = view.accounts.any { it.selected && !AccountLabelPolicy.isSafe(it.label) }
        return view.copy(
            selectedLabel = view.selectedLabel?.takeIf(AccountLabelPolicy::isSafe),
            accounts = accounts,
            lastSwitch = view.lastSwitch?.takeIf {
                AccountLabelPolicy.isSafe(it.from) && AccountLabelPolicy.isSafe(it.to) &&
                    AccountSwitchReasonText.isSafe(it.reason)
            },
            selectionUnknown = view.selectionUnknown || missingSelection || droppedSelected,
        )
    }

    private fun windows(a: HeadAccountView): String? = listOfNotNull(
        a.fiveHourUsedPercent?.let { "5h ${it.roundToInt()}%" },
        a.sevenDayUsedPercent?.let { "7d ${it.roundToInt()}%" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    private fun earliestReset(a: HeadAccountView): Long? =
        listOfNotNull(a.fiveHourResetEpochSeconds, a.sevenDayResetEpochSeconds).minOrNull()

    private fun ago(atMillis: Long): String = DoctorAge.ago(now() - atMillis)
}
