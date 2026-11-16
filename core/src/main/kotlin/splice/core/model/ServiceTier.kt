// NEW: gpt-speed (docs/specs/gpt-speed.md) — one speed tier a model offers, in its catalog's own words. The ChatGPT
// backend's `/models` lists each model's `service_tiers: [{ id, name, description }]`, such as
// `{ "id": "priority", "name": "Fast", "description": "2x speed, increased usage" }` (read 2026-11-15).
package splice.core.model

import splice.core.speed.Speed
import splice.core.speed.SpeedWords

/** One tier a model's catalog entry offers: its wire [id] (`priority`, `ultrafast`), its [name] and what the catalog
 *  says of it. */
public data class ServiceTier(
    val id: String,
    val name: String,
    val description: String = "",
) {
    /** The speed this tier serves at, or null for a tier splice has no word for. */
    public val speed: Speed? get() = SpeedWords.served(id)?.takeUnless { it == Speed.STANDARD }
}
