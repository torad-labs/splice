// NEW: 2026-09-22 — a model a provider's own list endpoint says it serves, as a head's catalog
// receives it. The endpoint is asked by :features-models (a socket, which :core may not open); the
// catalog decides what the answer means next to the rows splice.toml declares (ProviderConfig
// .catalogFor), so every reader of a catalog sees one roster and no reader asks the network.
package splice.core.model

/** One published model, before splice decides its window. [contextWindow] is null when the endpoint
 *  publishes none, never zero. [aliases] are the other spellings the endpoint says resolve to [id]:
 *  a declared row under any of them already covers this model, so it is not offered twice. */
public data class DiscoveredModel(
    val id: String,
    val label: String = "",
    val contextWindow: Long? = null,
    val aliases: List<String> = emptyList(),
) {
    /** [id] and every alias. */
    public val spellings: List<String> get() = listOf(id) + aliases
}
