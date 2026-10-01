// NEW: 2026-09-22 — a model a provider's own list endpoint says it serves, as a head's catalog
// receives it. The endpoint is asked by :features-models (a socket, which :core may not open); the
// catalog decides what the answer means next to the rows splice.toml declares (ProviderConfig
// .catalogFor), so every reader of a catalog sees one roster and no reader asks the network.
package splice.core.model

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
    val toolMode: String? = null,
    /** Backend-published serve ceiling, distinct from its default compaction window. */
    val maxContextWindow: Long? = null,
) {
    /** Whether the endpoint runs this model on the one-`exec` code-mode surface (V4-441): its `tool_mode`
     *  is `code_mode_only`, which is how the Codex backend marks the models it trained on that surface. */
    public val codeModeOnly: Boolean get() = toolMode == TOOL_MODE_CODE_MODE_ONLY

    /** [id] and every alias. */
    public val spellings: List<String> get() = listOf(id) + aliases
}

/** The `tool_mode` the Codex backend publishes for a model it runs on the code-mode surface alone. */
public const val TOOL_MODE_CODE_MODE_ONLY: String = "code_mode_only"

/** What each head's provider published at daemon start, by head key — the daemon's ModelRosters.
 *  Every reader that builds a head's catalog takes this one port, so the catalog a head boots with
 *  and the one the console validates an edit against see the same models. */
public fun interface HeadDiscoveredModels {
    public fun forHead(key: String): List<DiscoveredModel>
}
