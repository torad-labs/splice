// NEW: what the pool says about its pin (Nov 3, 2026). A pinned login that could not serve fell through to the pool's
// policy with nothing on the card but an Unpin and an empty lock, and the line naming a held model showed only in the
// moment after the Use now press. A pin now outlives the daemon, so the reason lives where every load reads it.
package splice.accounts.pool

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import splice.core.auth.AuthDescription
import splice.core.auth.REFUSAL_FIELD
import splice.core.failure.AccountBlock
import splice.core.failure.OwnerStep
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaFull
import splice.core.usage.QuotaFullWindow
import splice.core.util.LocalTimeText

/** Why a login serves no turn, in the words the console shows: the switch route's 409 and the pinned card alike. */
internal class HeldWords {
    fun noCredential(label: String): String = "'$label' has no credential file; sign in to it first"

    fun planSpent(label: String, plan: PlanLimit): String {
        val until = LocalTimeText().at(plan.resetEpochSeconds)
        return "${plan.windowWords} plan limit reached: '$label' serves no turn until $until"
    }

    /** The provider refused the login's credential (a 401): only a new sign-in clears it, so no retry time is named. */
    fun refused(label: String): String = "the provider refused '$label'; sign in to it again"

    /** The provider refused the login until its owner acts, which neither a sign-in nor a wait changes. */
    fun ownerStep(label: String, block: AccountBlock): String = when (block.step) {
        OwnerStep.ACCEPT_TERMS ->
            "'$label' is blocked until its owner accepts the provider's updated terms in ${block.where}"
    }

    /** No refusal: the login's usage reads full, and the pool spends a login with room before it. */
    fun readsFull(label: String, full: QuotaFull): String {
        val window = when (full.window) {
            QuotaFullWindow.FIVE_HOUR -> "5-hour"
            QuotaFullWindow.SEVEN_DAY -> "7-day"
        }
        val until = LocalTimeText().at(full.resetsAtEpochSeconds)
        return "'$label' reads its $window window at 100% until $until, so logins with room go first"
    }

    /** Each of [weeks] as the JSON the console reads: the model, and when the login serves it again. */
    fun modelWeeks(into: JsonArrayBuilder, weeks: List<PlanLimit>) = weeks.forEach { week ->
        into.addJsonObject {
            put("model", week.model)
            put("until_epoch_seconds", week.resetEpochSeconds)
        }
    }
}

/** The pool's word on its pin: why the next turn takes another login than the pinned one and which ([refusal]), or,
 *  while the pin serves, the models it cannot serve yet ([modelsHeld]). Null and empty when nothing is pinned, the
 *  pinned login is not one this pool holds now, or the pin serves every model. [refusal] follows the selector's own
 *  pick, `nextTargetLabel`: a login free to serve that the selector passes over for a full reading is named too. */
internal class PinReason(
    private val view: HeadAccountPoolView,
    private val descriptions: Map<String, AuthDescription> = emptyMap(),
) {
    private val words = HeldWords()
    private val pinned: HeadAccountView? =
        view.pinnedLabel?.let { label -> view.accounts.firstOrNull { it.label == label } }

    val refusal: String?
        get() {
            val account = pinned ?: return null
            val next = view.nextTargetLabel
            // The pin serves when the selector picks it, or, with no pick at all, when nothing holds it.
            val serves = if (next == null) account.available else next == account.label
            if (serves) return null
            val why = credentialWhy(account) ?: planWhy(account)
            return if (next == null) why else "$why. Turns use '$next' meanwhile."
        }

    val modelsHeld: List<PlanLimit>
        get() = pinned?.takeIf(HeadAccountView::available)?.quota?.holds?.models.orEmpty()

    /** The credential's reason, ahead of every plan one: a hold on a refused credential carries a retry time that
     *  nothing but a sign-in, or its owner's step, makes good. */
    private fun credentialWhy(account: HeadAccountView): String? {
        val label = account.label
        val credential = account.credential
        return when {
            !credential.present -> descriptions[label]?.fields?.get(REFUSAL_FIELD) ?: words.noCredential(label)
            credential.ownerBlock != null -> words.ownerStep(label, credential.ownerBlock)
            credential.excludedUntilEpochMillis != null -> words.refused(label)
            else -> null
        }
    }

    private fun planWhy(account: HeadAccountView): String {
        val label = account.label
        val wholeLogin = account.quota.holds.wholeLogin
        val full = account.quota.readsFull
        val blocked = view.blockedUntilEpochSecondsByLabel[label]
        return when {
            wholeLogin != null -> words.planSpent(label, wholeLogin)
            account.available && full != null -> words.readsFull(label, full)
            blocked != null -> "'$label' is held until ${LocalTimeText().at(blocked)}"
            else -> "'$label' serves no turn right now"
        }
    }
}
