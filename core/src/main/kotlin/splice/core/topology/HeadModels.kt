// NEW: which model rows a head serves from its provider's roster, or the sentence that says why it serves none.
package splice.core.topology

import splice.core.model.ModelEntry

/** The rows [head] serves from a provider roster: the roster as it stands for a forwarded login, otherwise the
 *  pinned model and the head's own list and tiers checked against what the provider declares or can list. A refusal
 *  is a value carrying the words an operator reads, so a writer that validates a topology catches nothing. */
internal class HeadModels(private val provider: ProviderConfig) {
    sealed class Roster {
        class Rows(val entries: List<ModelEntry>) : Roster()

        class Refused(val detail: String) : Roster()
    }

    fun select(head: HeadConfig, roster: List<ModelEntry>): Roster {
        // Legacy pins/allowlists never constrain a forwarded login. Provider rows are labels and rates,
        // not the client's model surface; launches and materialization leave that surface untouched.
        if (provider.clientPicksModels) return Roster.Rows(roster)
        val requested = head.models
        val refusal = pinRefusal(head)
            ?: slotRefusal(head, roster)
            ?: requested?.let { listRefusal(it) ?: undeclaredRefusal(head, it, roster) }
        return when {
            refusal != null -> Roster.Refused(refusal)
            requested == null -> Roster.Rows(provider.withPinned(roster, head.pinnedModel))
            else -> chosen(head, requested, roster)
        }
    }

    // The head's pinned model always has a row (2026-09-23): it is the one model the operator named, and a
    // catalog without it refuses every turn the head was launched to serve. With no declared rows the roster is
    // whatever the endpoint listed, which can omit the pinned id — a retired model, a discovery filter, an alias
    // the roster does not spell, a start it missed.
    private fun pinRefusal(head: HeadConfig): String? =
        "pinned_model is required on a head whose provider is not your own Claude login"
            .takeIf { head.pinnedModel.isBlank() }

    /** The first model slot of [head] that names a model its allowlist or its provider does not carry. */
    private fun slotRefusal(head: HeadConfig, roster: List<ModelEntry>): String? {
        for ((slot, id) in head.modelSlots) {
            if (head.models != null && head.models.none { it.id == id }) {
                return "model_slots.$slot names a model outside the head models allowlist; " +
                    "add it to models or remove the tier"
            }
            if (roster.none { it.id == id } && !provider.listsModels) {
                return "model_slots.$slot names model '$id' neither declared nor discovered by " +
                    "provider '${head.provider}'"
            }
        }
        return null
    }

    /** Why a head's own model list cannot stand, whatever the provider carries. */
    private fun listRefusal(requested: List<HeadModel>): String? {
        val slots = requested.mapNotNull { it.slot?.lowercase() }
        return when {
            requested.isEmpty() -> "head model list must not be empty"
            requested.map { it.id }.distinct().size != requested.size -> "head model list contains duplicates"
            !slots.all { it in headModelSlots } -> "unknown Claude model slot"
            slots.distinct().size != slots.size -> "head model slots contain duplicates"
            else -> null
        }
    }

    // An id the roster lacks is not served at this start. Where an endpoint could have listed it, that is the
    // endpoint's doing (retired, filtered, or not answered in time) and the row is dropped, never the head;
    // HeadBoot names it in daemon.log. Where nothing could have listed it, it is a misspelling, refused as it was
    // before discovery existed.
    private fun undeclaredRefusal(head: HeadConfig, requested: List<HeadModel>, roster: List<ModelEntry>): String? {
        val byId = roster.associateBy(ModelEntry::id)
        val unknown = requested.firstOrNull { it.id !in byId && !provider.listsModels } ?: return null
        return "head model '${unknown.id}' is not declared by provider '${head.provider}', which lists no models"
    }

    // A row the head's allowlist names is the operator's decision, whichever list supplied it, so it is DECLARED
    // for this head: it keeps the allowlist's order and may stand behind a tier. The pinned id can come from
    // OUTSIDE the TOML: resolveHeadConfig swaps pinned_model with the pinnedModel/grokModel knob for oauth heads,
    // and env/config.json/PATCH override that knob — so a self-consistent splice.toml still fails here. Name the
    // id, the roster, and the provenance, or the operator greps the TOML for a value that is not in it (DR-44a).
    private fun chosen(head: HeadConfig, requested: List<HeadModel>, roster: List<ModelEntry>): Roster {
        val byId = roster.associateBy(ModelEntry::id)
        val selected = requested.mapNotNull { model ->
            byId[model.id]?.copy(discovered = false)
                ?: provider.pinnedOnly(model.id).takeIf { model.id == head.pinnedModel }
        }
        if (selected.any { it.id == head.pinnedModel }) return Roster.Rows(selected)
        return Roster.Refused(
            "pinned model '${head.pinnedModel}' is not in the head model list " +
                "[${selected.joinToString(", ") { it.id }}]; it was set by pinned_model in splice.toml " +
                "unless the pinnedModel/grokModel knob (env, config.json, or PATCH) overrode it",
        )
    }
}
