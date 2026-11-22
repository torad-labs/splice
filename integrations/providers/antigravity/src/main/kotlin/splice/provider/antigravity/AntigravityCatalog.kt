// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 1). Antigravity's models as one more provider's
// ModelCatalog, under the selector prefix `claude-antigravity--`, each with the reasoning levels agy lists for it, so
// the catalog splice publishes (CatalogBuild) gives Claude Desktop's picker their effort control as it does any other
// login provider's. A model whose id Claude's clients keep as their own (agy's Claude Opus and Sonnet) is left out:
// Desktop runs a Claude id on Claude Code, never on agy.
package splice.provider.antigravity

import splice.core.model.ClientModelNames
import splice.core.model.DiscoveredModel
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelListing
import splice.core.model.ModelRuntimeMetadata

/** The selector prefix Antigravity's models carry, which the Desktop launcher reads to start agy in place of Claude
 *  Code. */
internal const val ANTIGRAVITY_PREFIX: String = "claude-antigravity--"

/** Builds the catalog of an [AgyRoster]. */
public class AntigravityCatalog {
    /** [roster]'s models as a catalog, or null when it offers none splice can list. */
    public fun of(roster: AgyRoster): ModelCatalog? {
        val models = roster.models.filterNot { ClientModelNames.kept(it.id) }
        if (models.isEmpty()) return null
        return ModelCatalog(
            discoveryPrefix = ANTIGRAVITY_PREFIX,
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
