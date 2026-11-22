// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 1). Antigravity's models as one more provider's
// ModelCatalog, under the selector prefix `claude-antigravity--`, each with the reasoning levels agy lists for it, so
// the catalog splice publishes (CatalogBuild) gives Claude Desktop's picker their effort control as it does any other
// login provider's. A model whose id Claude's clients keep as their own (agy's Claude Opus and Sonnet) is left out:
// Desktop reads a Claude id as Claude's own model and runs it on Claude Code, so it would stand in for the real Claude
// row, never run on agy.
package splice.provider.antigravity

import splice.core.model.ClientModelNames
import splice.core.model.DiscoveredModel
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelListing
import splice.core.model.ModelRuntimeMetadata
import splice.core.topology.gateway.GatewaySelectorPrefixes

/** Antigravity's provider key: the name its selectors carry, `claude-antigravity--<model>`, which Desktop's launcher
 *  reads to start agy in place of Claude Code. */
public const val ANTIGRAVITY_KEY: String = "antigravity"

/** Builds the catalog of an [AgyRoster]. */
internal class AntigravityCatalog {
    /** [roster]'s models as a catalog, or null when it offers none splice can list. */
    fun of(roster: AgyRoster): ModelCatalog? {
        val models = roster.models.filterNot { ClientModelNames.kept(it.id) }
        if (models.isEmpty()) return null
        return ModelCatalog(
            discoveryPrefix = GatewaySelectorPrefixes.canonical(ANTIGRAVITY_KEY),
            models = models.map { entry(it, roster.contextWindow) },
            defaultContextWindow = roster.contextWindow,
            pinnedModel = roster.current?.takeIf { current -> models.any { it.id == current } }.orEmpty(),
        )
    }

    private fun entry(model: AgyModel, window: Long): ModelEntry {
        val listing = ModelListing(
            efforts = model.efforts,
            defaultEffort = model.defaultEffort,
            reasons = model.efforts.isNotEmpty(),
        )
        return ModelEntry(
            id = model.id,
            label = model.label,
            contextWindow = window,
            discovered = true,
            listed = DiscoveredModel(
                model.id,
                model.label,
                window,
                runtimeMetadata = ModelRuntimeMetadata(listing = listing),
            ),
        )
    }
}
