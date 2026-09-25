// NEW: V4-220 (2026-09-25) — what add-model offers and what it writes, moved out of AddModelVerb so the
// console's add-model decides exactly as `splice add-model` does: the same heads, the same remaining rows,
// the same roster edit and the same fail-closed parse. Only the picking differs (prompts, or a request).
package splice.configuration.add

import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader

private const val OPENROUTER = "openrouter"

/** One OpenRouter head and the catalogue rows it cannot reach yet ([remaining]). */
internal data class AddModelOffer(
    val headKey: String,
    val providerKey: String,
    val headDeclaresModels: Boolean,
    val providerIds: Set<String>,
    val remaining: List<AddModel>,
)

/** The rows picked from one [offer], in the catalogue's order. */
internal data class AddModelPlan(val offer: AddModelOffer, val models: List<AddModel>)

internal class AddModelOffers {

    /** Every head of the OpenRouter provider, in splice.toml's order. */
    fun of(topology: Topology): List<AddModelOffer> {
        val profile = AddProfiles().find(OPENROUTER) ?: return emptyList()
        return topology.heads.filter { it.value.provider == profile.headKey }.map { (headKey, head) ->
            val providerIds = topology.providers.getValue(head.provider).models.map { it.id }.toSet()
            // REACHABLE, not merely present. A head that declares `models = [...]` is a ROSTER:
            // Topology.modelsFor returns it verbatim and ignores every other provider row, so a model
            // already in the provider table but absent from the array is still invisible on /v1/models
            // and must stay on offer (V4-34 redo 2026-09-17).
            val reachable = head.models?.map { it.id }?.toSet() ?: providerIds
            val remaining = profile.models.filter { it.id !in reachable }
            AddModelOffer(headKey, head.provider, head.models != null, providerIds, remaining)
        }
    }
}

/** [plan] composed into the config text. [roster] is the edit as a seam (the DR-66 StarterWrite
 *  precedent): the fail-closed re-parse below is only testable on the production path if a test can
 *  hand it a composition that does not parse. Production always passes the real editor. */
internal class AddModelCompose(private val roster: RosterEditor) {

    /** The composed text; throws [AddRefused] when the roster cannot be edited or the result does not parse. */
    operator fun invoke(existing: String, plan: AddModelPlan): String {
        val key = plan.offer.providerKey
        // Only ids the provider table does not already carry: on the shipped starter every curated
        // id is already a provider row and the roster is what was missing, so a second copy here
        // would be a duplicate the catalog silently collapses. No rows means the file keeps exactly
        // its trailing newline rather than gaining a blank line (V4-34 redo 2026-09-17).
        val rows = plan.models.filter { it.id !in plan.offer.providerIds }.flatMap { model ->
            listOf(
                "[[providers.$key.models]]",
                "id = \"${model.id}\"",
                "label = \"${model.label}\"",
                "context_window = ${model.contextWindow}",
            )
        }
        val extra = if (rows.isEmpty()) "\n" else rows.joinToString("\n", prefix = "\n", postfix = "\n")
        val rostered = if (plan.offer.headDeclaresModels) {
            roster(existing, plan.offer.headKey, plan.models.map { it.id })
        } else {
            existing
        }
        val composed = rostered.trimEnd('\n') + extra
        refuseUnparseable(composed)
        return composed
    }

    /** FAIL CLOSED (review 2026-09-17 (2)): the composition is parsed by the loader `splice` itself
     *  boots with BEFORE any byte reaches the operator's file, so a corrupted edit refuses instead
     *  of riding ATOMIC_MOVE over a working splice.toml. Nothing is written on the refusal — not
     *  even the temp file, which is created after this returns. */
    private fun refuseUnparseable(composed: String) {
        val failure = Cancellables.runCatchingCancellable { TopologyLoader.parse(composed) }.exceptionOrNull() ?: return
        throw AddRefused(
            "the roster edit does not parse, so splice.toml was left untouched: " + SafeFailureText.render(failure),
        )
    }
}
