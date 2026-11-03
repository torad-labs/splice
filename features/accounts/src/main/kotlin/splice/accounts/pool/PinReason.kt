// NEW: what the pool says about its pin (Nov 3, 2026). A pinned login that could not serve fell through to the pool's
// policy with nothing on the card but an Unpin and an empty lock, and the line naming a held model showed only in the
// moment after the Use now press. A pin now outlives the daemon, so the reason lives where every load reads it.
package splice.accounts.pool

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import splice.core.auth.AuthDescription
import splice.core.auth.REFUSAL_FIELD
import splice.core.usage.PlanLimit
import splice.core.util.LocalTimeText

/** Why a login serves no turn, in the words the console shows: the switch route's 409 and the pinned card alike. */
internal class HeldWords {
    fun noCredential(label: String): String = "'$label' has no credential file; sign in to it first"

    fun planSpent(label: String, plan: PlanLimit): String {
        val until = LocalTimeText().at(plan.resetEpochSeconds)
        return "${plan.windowWords} plan limit reached: '$label' serves no turn until $until"
    }

    /** Each of [weeks] as the JSON the console reads: the model, and when the login serves it again. */
    fun modelWeeks(into: JsonArrayBuilder, weeks: List<PlanLimit>) = weeks.forEach { week ->
        into.addJsonObject {
            put("model", week.model)
            put("until_epoch_seconds", week.resetEpochSeconds)
        }
    }
}

/** The pool's word on its pin: why the pinned login serves no turn and where the turns go instead ([refusal]), or,
 *  while it serves, the models it cannot serve yet ([modelsHeld]). Null and empty when nothing is pinned, the pinned
 *  login is not one this pool holds now, or the pin serves every model. */
internal class PinReason(
    private val view: HeadAccountPoolView,
    private val descriptions: Map<String, AuthDescription> = emptyMap(),
) {
    private val pinned: HeadAccountView? =
        view.pinnedLabel?.let { label -> view.accounts.firstOrNull { it.label == label } }

    val refusal: String?
        get() {
            val account = pinned?.takeUnless(HeadAccountView::available) ?: return null
            val elsewhere = view.nextTargetLabel?.takeIf { it != account.label }
            val why = why(account)
            return if (elsewhere == null) why else "$why. Turns use '$elsewhere' meanwhile."
        }

    val modelsHeld: List<PlanLimit>
        get() = pinned?.takeIf(HeadAccountView::available)?.quota?.holds?.models.orEmpty()

    private fun why(account: HeadAccountView): String {
        val label = account.label
        val words = HeldWords()
        val blocked = view.blockedUntilEpochSecondsByLabel[label]
        return when {
            !account.credential.present ->
                descriptions[label]?.fields?.get(REFUSAL_FIELD) ?: words.noCredential(label)
            account.quota.holds.wholeLogin != null -> words.planSpent(label, account.quota.holds.wholeLogin)
            blocked != null -> "'$label' is held until ${LocalTimeText().at(blocked)}"
            else -> "'$label' serves no turn right now"
        }
    }
}
