// NEW: gpt-speed (docs/specs/gpt-speed.md) — which tiers a model offers, as its head's catalog lists them. Codex sends
// a tier only when the model's catalog entry lists it (codex-rs ModelInfo::service_tier_for_request, rust-v0.169.0),
// and its /fast and /ultrafast come from the same list; both questions are asked here.
package splice.core.speed

import splice.core.model.HeadDiscoveredModels
import splice.core.model.ListedModels
import splice.core.model.ServiceTier

/** Each head's catalog, asked which speed tiers a model offers. Read on every ask, so an hourly refresh of the list
 *  reaches the next turn and the next command. */
public class SpeedCatalog(private val roster: HeadDiscoveredModels) {
    /** The tiers [model] offers on head [head], in the catalog's order; none when the list names no tier for it, or
     *  does not list the model at all. */
    public fun tiersOf(head: String, model: String): List<ServiceTier> =
        ListedModels.matching(roster.metadataForHead(head), model)?.serviceTiers.orEmpty()

    /** The tier [model] offers on head [head] for [speed], or null: standard is no tier at all. */
    public fun tierFor(head: String, model: String, speed: Speed): ServiceTier? =
        if (speed == Speed.STANDARD) null else tiersOf(head, model).firstOrNull { it.speed == speed }
}
