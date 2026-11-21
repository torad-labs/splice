// NEW: 2026-09-22 — a model a provider's own list endpoint says it serves, as a head's catalog
// receives it. The endpoint is asked by :features-models (a socket, which :core may not open); the
// catalog decides what the answer means next to the rows splice.toml declares (ProviderConfig
// .catalogFor), so every reader of a catalog sees one roster and no reader asks the network.
package splice.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One published model, before splice decides its window. [contextWindow] is null when the endpoint
 *  publishes none, never zero. [aliases] are the other spellings the endpoint says resolve to [id]:
 *  a declared row under any of them already covers this model, so it is not offered twice. [rates] is the
 *  card the endpoint lists for it (OpenRouter's `pricing`), null when it lists none or a price splice cannot
 *  read (V4-438): a row with no card of its own takes it, so a model is priced wherever it is listed.
 *  [toolMode] is the endpoint's own `tool_mode` for it (the Codex backend publishes one per model). */
public data class DiscoveredModel(
    val id: String,
    val label: String = "",
    val contextWindow: Long? = null,
    val aliases: List<String> = emptyList(),
    val rates: ModelRates? = null,
    /** Backend-published serve ceiling, distinct from its default compaction window. */
    val maxContextWindow: Long? = null,
    val runtimeMetadata: ModelRuntimeMetadata = ModelRuntimeMetadata(),
) {
    public val toolMode: String? get() = runtimeMetadata.toolMode
    public val supportsImageDetailOriginal: Boolean get() = runtimeMetadata.supportsImageDetailOriginal
    public val modelMessages: kotlinx.serialization.json.JsonObject? get() = runtimeMetadata.modelMessages
    public val useResponsesLite: Boolean get() = runtimeMetadata.useResponsesLite
    public val serviceTiers: List<ServiceTier> get() = runtimeMetadata.serviceTiers
    public val listing: ModelListing get() = runtimeMetadata.listing

    /** Whether the endpoint runs this model on the one-`exec` code-mode surface (V4-441): its `tool_mode`
     *  is `code_mode_only`, which is how the Codex backend marks the models it trained on that surface. */
    public val codeModeOnly: Boolean get() = toolMode == TOOL_MODE_CODE_MODE_ONLY

    /** [id] and every alias. */
    public val spellings: List<String> get() = listOf(id) + aliases
}

/** Optional runtime facts retained as one published snapshot, independently of picker eligibility. */
public data class ModelRuntimeMetadata(
    val supportsImageDetailOriginal: Boolean = false,
    val modelMessages: kotlinx.serialization.json.JsonObject? = null,
    val useResponsesLite: Boolean = false,
    val toolMode: String? = null,
    /** gpt-speed: the speed tiers the endpoint lists for the model (`service_tiers`), in its own words. */
    val serviceTiers: List<ServiceTier> = emptyList(),
    val listing: ModelListing = ModelListing(),
)

/** What a provider's list says about a model in its own words, beside its id, name and window: its [description], the
 *  reasoning levels it takes ([efforts], in the provider's order and spelling; empty when it names none), the level it
 *  runs at when a request names none ([defaultEffort]), and whether it says the model takes a reasoning setting at all
 *  ([reasons]: true with levels, and for a list that names the setting and no levels, as OpenRouter's does). Every
 *  reader of a model's reasoning levels takes them from here, so none is written down in splice. */
@Serializable
public data class ModelListing(
    val description: String = "",
    val efforts: List<String> = emptyList(),
    @SerialName("default_effort") val defaultEffort: String? = null,
    val reasons: Boolean = false,
    /** The provider's model row, retained for clients that understand properties splice does not interpret. */
    val source: kotlinx.serialization.json.JsonObject? = null,
)

/** The `tool_mode` the Codex backend publishes for a model it runs on the code-mode surface alone. */
internal const val TOOL_MODE_CODE_MODE_ONLY: String = "code_mode_only"

/** What each head's provider published at daemon start, by head key — the daemon's ModelRosters.
 *  Every reader that builds a head's catalog takes this one port, so the catalog a head boots with
 *  and the one the console validates an edit against see the same models. */
public fun interface HeadDiscoveredModels {
    public fun forHead(key: String): List<DiscoveredModel>

    /** Metadata may also describe hidden models selected explicitly, without adding them to the picker. */
    public fun metadataForHead(key: String): List<DiscoveredModel> = forHead(key)

    /** Returns once [key]'s list is the one its provider gives the credential the head presents now. A head whose
     *  credential changed since its list was asked is asked again first, and the wait ends with that answer, or with
     *  the list it keeps when no answer comes. A turn waits on this before it is admitted, so the turn sent the moment
     *  after a sign-in reads that sign-in's list (fix 3, Oct 27). The default has nothing to wait for. */
    public suspend fun awaitCurrent(key: String): Unit = Unit
}

/** Which listed model stands for a model id, as Codex finds one (codex-rs models-manager manager.rs,
 *  find_model_by_longest_prefix): the listed model whose id or alias is the longest prefix of it. */
public object ListedModels {
    /** The listed model [model] takes its facts from, or null when no listed id or alias begins it. Hidden models
     *  count: a list marks them too. */
    public fun matching(listed: List<DiscoveredModel>, model: String): DiscoveredModel? {
        val id = model.trim().lowercase()
        return listed.flatMap { row -> row.spellings.map { it.trim().lowercase() to row } }
            .filter { (spelling, _) -> spelling.isNotEmpty() && id.startsWith(spelling) }
            .maxByOrNull { (spelling, _) -> spelling.length }
            ?.second
    }
}
