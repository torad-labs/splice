// NEW: 2026-11-20 — the model catalog: every model a signed-in, configured provider's own list publishes, once per
// provider, each with what that list says of it (its id, its name, its description, the reasoning levels it takes,
// its window). It is pure provider fact, credential-free, kept as one file in splice's state folder
// (CatalogFile, StatePaths.modelCatalogFile). Every serving and routing view (gateway discovery, the terminal's
// picker, inferenceModels, /api/models) is built from these facts elsewhere, never duplicated here.
package splice.app.desktop

import kotlinx.serialization.Serializable

/** One model as its provider publishes it. [label] is the provider's published name, else splice.toml's label for
 *  the row, else [id]. [description], [reasoningLevels], [defaultReasoningLevel] and [reasons] are the provider's own
 *  words (DiscoveredModel.listing); absent when the provider's list names nothing, never guessed. [contextWindow] is
 *  the serving ceiling (ModelServeWindows.forRow), independent of any compaction target. */
@Serializable
internal data class CatalogModel(
    val provider: String,
    val id: String,
    val label: String,
    val description: String = "",
    val reasoningLevels: List<String> = emptyList(),
    val defaultReasoningLevel: String? = null,
    val reasons: Boolean = false,
    val contextWindow: Long = 0,
)

/** The whole catalog: one entry per (provider, id), in the order [CatalogBuild] was given the signed-in providers. */
@Serializable
internal data class ProviderCatalog(val models: List<CatalogModel> = emptyList())
