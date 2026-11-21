// NEW: 2026-11-20 — the model catalog: every model a signed-in, configured provider's own list publishes, once per
// provider, each with what that list says of it (its id, its name, its description, the reasoning levels it takes,
// its window). It is pure provider fact, credential-free, kept as one file in splice's state folder
// (CatalogFile, StatePaths.modelCatalogFile). Every serving and routing view (gateway discovery, the terminal's
// picker, inferenceModels, /api/models) is built from these facts elsewhere, never duplicated here.
package splice.app.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/** One model as its provider publishes it. [label] keeps a meaningful provider name, or the configured label, or a
 *  known model name or readable form of [id]. [description] and reasoning fill missing provider and configured facts
 *  from the known-model catalog. [contextWindow] is
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
    @Transient val source: JsonObject? = null,
)

/** The whole catalog: one entry per (provider, id), in the order [CatalogBuild] was given the signed-in providers. */
@Serializable
internal data class ProviderCatalog(val models: List<CatalogModel> = emptyList())
