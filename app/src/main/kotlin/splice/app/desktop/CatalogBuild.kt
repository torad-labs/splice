// NEW: 2026-11-20 — the model catalog (ProviderCatalog) built from what the signed-in, configured providers publish.
// This is the producer: it knows nothing of Desktop, the door or any other consumer, only how to turn one provider's
// live ModelCatalog into the catalog's facts. Which heads are signed in and configured, and which of several heads on
// one provider to read, is the caller's decision (CatalogSource per provider); this never asks a socket or an auth
// store itself.
package splice.app.desktop

import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelListing
import splice.core.model.ModelServeWindows

/** One signed-in, configured provider's live catalog, as the caller reads it: [provider] is the key splice.toml (or
 *  the provider's own name) gives it, [catalog] the model catalog of the head the caller chose to read it from. */
internal data class CatalogSource(val provider: String, val catalog: ModelCatalog)

internal class CatalogBuild {
    /** The catalog of [signedIn]'s providers: every model each one's catalog carries now, each with the facts its
     *  provider's own list published for it. A model two providers serve is listed under each; a model one provider
     *  serves under two rows (a declared row and its `[1m]` variant) is listed once, on the first. */
    fun of(signedIn: List<CatalogSource>): ProviderCatalog {
        val models = signedIn.flatMap { source ->
            val live = source.catalog.live()
            live.models.map { entry -> model(source.provider, live, entry) }
        }.distinctBy { it.provider to it.id }
        return ProviderCatalog(models)
    }

    private fun model(provider: String, catalog: ModelCatalog, entry: ModelEntry): CatalogModel {
        val id = catalog.stripSuffixes(entry.id)
        val listing = entry.listed?.listing ?: ModelListing()
        val publishedLabel = entry.listed?.label?.ifBlank { null }
        val label = publishedLabel ?: entry.label.ifEmpty { id }
        val description = listing.description.ifBlank { entry.description }
        return CatalogModel(
            provider = provider,
            id = id,
            label = label,
            description = description,
            reasoningLevels = listing.efforts,
            defaultReasoningLevel = listing.defaultEffort,
            reasons = listing.reasons,
            contextWindow = ModelServeWindows.forRow(catalog, entry.id),
        )
    }
}
