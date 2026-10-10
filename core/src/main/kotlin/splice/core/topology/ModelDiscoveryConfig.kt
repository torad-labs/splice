// NEW: 2026-09-22 — which of the models a provider publishes join its heads' pickers.
//
// WHY A FILTER AT ALL. A model list is the vendor's whole catalogue, not its chat catalogue, and most
// vendors do not say which is which. Measured 2026-09-22: api.x.ai/v1/models lists six grok-imagine
// image and video models beside the chat models with no capability field to tell them apart, and
// api.meta.ai/v1/models lists sam-3.1, muse-voice-transcribe-1.0 and muse-image-1.0 the same way.
// Where an endpoint DOES say (OpenRouter's output modalities and supported parameters, the Codex
// backend's visibility), the parser drops what cannot run a turn by itself; what no endpoint says,
// the operator says here, once per provider, in the vendor's own naming.
package splice.core.topology

import kotlinx.serialization.Serializable

/** `discovery = { include = [...], exclude = [...], family = "vast" }` on a provider: how splice finds and places it. The
 *  include and exclude patterns are globs over the published model id, where `*` matches any run of characters and
 *  everything else is literal. `family` says whose machine runs the provider, which sits here because discovery covers where
 *  splice finds and places a provider, its models and its machine family. */
@Serializable
public data class ModelDiscoveryConfig(
    /** Only ids matching one of these join the picker. Empty = every published model. */
    val include: List<String> = emptyList(),
    /** Ids matching one of these never join, whatever [include] says. `["*"]` turns discovery off. */
    val exclude: List<String> = emptyList(),
    /** Oct 10, 2026: whose machine runs this provider, when its address cannot say. A rented GPU reached through an SSH
     *  tunnel answers on loopback like a server on this machine, so `family = "vast"` groups it as vast.ai in the console
     *  where the loopback rule would say This computer. Absent = [ProviderFamilyRule] decides from the auth kind, the
     *  provider's key and its address. */
    val family: String? = null,
) {
    /** Whether a published [id] joins the picker. */
    public fun admits(id: String): Boolean =
        (include.isEmpty() || include.any { matches(it, id) }) && exclude.none { matches(it, id) }

    private fun matches(pattern: String, id: String): Boolean =
        Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }).matches(id)
}
