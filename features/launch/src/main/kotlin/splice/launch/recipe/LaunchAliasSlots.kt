// Split from LaunchService.kt (Oct 13, concentration law): which model each of Claude Code's tier slots (opus, sonnet,
// haiku, fable) names for a head, declared or positional. LaunchService plants the answer in the launch's environment.
package splice.launch.recipe

import splice.launch.LaunchSpec

internal object LaunchAliasSlots {
    // opus/sonnet/haiku/fable → Claude Code's tier env slots.
    //
    // A head that DECLARES slots gets EXACTLY its declared tiers and nothing else (2026-08-30):
    // the positional scheme maps four slots onto catalog order, so a roster without sol/terra/luna
    // names lands two models in four slots — the picker then shows the same names repeatedly, and
    // the catalog's ORDER becomes load-bearing (splice.toml still carries a banner saying so).
    // Filling the UNDECLARED tiers positionally just re-created the duplication on any roster
    // smaller than four (a 2-model head still planted one model in 3 slots — codex redo verdict),
    // so declaring anything retires positional order outright: a tier the head does not declare is
    // not emitted, never pointed at an already-claimed model.
    fun of(spec: LaunchSpec): List<Pair<String, String>> {
        // V4-449: the client picks its own models on a head that forwards its login, so no tier is planted.
        if (spec.gateway.forwardClientAuth) return emptyList()
        val offered = spec.models.availableModelIds
        val ids = (listOf(spec.models.pinnedModel) + (spec.models.tiers.candidates ?: offered)).distinct()
        val declared = declaredSlots(spec)
        if (spec.models.tiers.byTier.isNotEmpty()) {
            return listOf("OPUS", "SONNET", "HAIKU", "FABLE").mapNotNull { slot ->
                declared[slot.lowercase()]?.let { model -> slot to model }
            }
        }
        val fallback = positionalTiers(ids, offered = offered)
        return listOf(
            "OPUS" to fallback.frontier,
            "SONNET" to fallback.mid,
            "HAIKU" to fallback.fast,
            // Fable shares the frontier (opus) slot — positional at(2) used to park it on luna
            // whenever sol/terra/luna were listed in that order.
            "FABLE" to fallback.frontier,
        )
    }

    private data class PositionalTiers(val frontier: String, val mid: String, val fast: String)

    /** The pre-slot heuristic, byte-identical for a head that declares nothing (every head that
     *  existed before slots): Codex 5.6 tier names when the catalog carries them, else catalog
     *  order, with haiku preferring a mini/fast id. [ids] starts with the pinned model, so
     *  `ids.first()` is the never-empty floor. Catalog ORDER and the mini/fast guess read [ids]
     *  only; a tier NAME may also come from [offered] (see [TierNames]). */
    private fun positionalTiers(ids: List<String>, offered: List<String>): PositionalTiers {
        val names = TierNames(ids, offered)
        val miniOrFast = ids.firstOrNull {
            it.contains("mini", ignoreCase = true) || it.contains("fast", ignoreCase = true)
        }
        return PositionalTiers(
            frontier = names.of("sol") ?: ids.first(),
            mid = names.of("terra") ?: ids.getOrNull(1) ?: ids.first(),
            fast = names.of("luna") ?: miniOrFast ?: ids.getOrNull(1) ?: ids.first(),
        )
    }

    /** Codex tier names (the id's last `-` segment: sol, terra, luna). A candidate in [ids] wins; else
     *  a model the endpoint listed ([offered]) may carry the name (2026-09-23), because the name is the
     *  backend's own statement of the tier, which is what lets a Codex provider declare no rows at
     *  all. Only a model in the pinned model's family qualifies: gpt-5.6-sol still places
     *  gpt-5.6-terra and gpt-5.6-luna, never a newer family's luna, and a discovered openai/gpt-6-sol
     *  on an OpenRouter head pinned to a Claude model is placed nowhere. */
    private class TierNames(private val ids: List<String>, private val offered: List<String>) {
        private val family = ids.first().substringBeforeLast('-', missingDelimiterValue = "")

        fun of(tier: String): String? =
            ids.firstOrNull { isTier(it, tier) } ?: offered.firstOrNull { inFamily(it) && isTier(it, tier) }

        private fun inFamily(id: String): Boolean = family.isNotEmpty() && id.startsWith("$family-")

        private fun isTier(id: String, tier: String): Boolean =
            id.substringAfterLast('-', missingDelimiterValue = id).equals(tier, ignoreCase = true)
    }

    /** slot name -> model id, keeping only slots this catalog actually offers. A declared slot
     *  naming a model the head does not serve is ignored rather than planted, so a stale row in
     *  splice.toml cannot point a tier at a model every turn would 400 on. Keyed by tier, so two
     *  tiers on one model both survive; LaunchModelEnvironment plants the repeat off the picker. */
    private fun declaredSlots(spec: LaunchSpec): Map<String, String> =
        spec.models.tiers.byTier
            .filterValues { it == spec.models.pinnedModel || it in spec.models.availableModelIds }
            .mapKeys { (slot, _) -> slot.lowercase() }
}
