// NEW: 2026-11-20 — the model catalog (ProviderCatalog) built from what the signed-in, configured providers publish.
// This is the producer: it knows nothing of Desktop, the door or any other consumer, only how to turn one provider's
// live ModelCatalog into the catalog's facts. Which heads are signed in and configured, and which of several heads on
// one provider to read, is the caller's decision (CatalogSource per provider); this never asks a socket or an auth
// store itself.
package splice.app.desktop.catalog

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        val known = KnownModelMetadata.find(catalog.vendorBaseUrl, id)
        val label = label(id, entry, known)
        val description = listing.description.ifBlank { entry.description }.ifBlank { known?.description.orEmpty() }
        val knownLevels = known?.reasoningLevels.orEmpty().takeUnless { noReasoning(listing) }.orEmpty()
        val levels = listing.efforts.ifEmpty { knownLevels }
        return CatalogModel(
            provider = provider,
            id = id,
            label = label,
            description = description,
            reasoningLevels = levels,
            defaultReasoningLevel = listing.defaultEffort ?: known?.defaultEffort?.takeIf { it in levels },
            reasons = listing.reasons || levels.isNotEmpty(),
            contextWindow = ModelServeWindows.forRow(catalog, entry.id),
            source = listing.source,
        )
    }

    private fun noReasoning(listing: ModelListing): Boolean {
        val capabilities = listing.source?.get("capabilities") as? JsonObject
        val effort = capabilities?.get("effort") as? JsonObject
        return listing.source?.get("supports_reasoning") == JsonPrimitive(false) ||
            effort?.get("supported") == JsonPrimitive(false)
    }

    private fun label(id: String, entry: ModelEntry, known: KnownModel?): String {
        val published = entry.listed?.label?.takeIf(String::isNotBlank)
        val declared = entry.label.takeIf(String::isNotBlank)
        return published?.takeUnless { rawName(it, id) }
            ?: declared?.takeUnless { rawName(it, id) }
            ?: known?.label?.takeIf(String::isNotBlank)
            ?: readable(id, published ?: declared)
    }

    private fun readable(id: String, selected: String?): String {
        val file = filename(id)
        if (!isModelFile(file)) return selected?.takeIf { it != id } ?: words(file)
        val stem = file.replace(MODEL_EXTENSION, "").replace(QUANTIZATION_SUFFIX, "").replace(REVISION_SUFFIX, "")
        return words(stem.ifBlank { file })
    }

    private fun words(name: String): String = name.split('-', '_').joinToString(" ") { word ->
        word.replaceFirstChar { it.titlecase() }
    }

    private fun rawName(name: String, id: String): Boolean =
        name.equals(id, ignoreCase = true) || name.equals(filename(id), ignoreCase = true) || isModelFile(name)

    private fun filename(id: String): String = id.substringAfterLast('/').substringAfterLast('\\')

    private fun isModelFile(name: String): Boolean =
        MODEL_EXTENSION.containsMatchIn(name) || QUANTIZATION_SUFFIX.containsMatchIn(name)
}

private val MODEL_EXTENSION = Regex("(?i)\\.(?:gguf|safetensors|onnx|pth|pt|bin)$")
private val QUANTIZATION_SUFFIX = Regex("(?i)[-_](?:iq[1-8]|q[1-8])[_-][a-z0-9_-]+$")
private val REVISION_SUFFIX = Regex("(?i)[-_](?=[0-9a-f]{8,16}$)(?=[0-9a-f]*[a-f])[0-9a-f]{8,16}$")
