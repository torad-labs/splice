// NEW: Nov 21, 2026 — split from HeadOverrideWrite.kt along its seam: where a per-command write lands is decided here,
// and the write and its reply stay there.
//
// gateway-no-heads: a knob a turn is served by (providerCarriedKnobs) is the provider's, since the gateway serves a
// provider with no head. It lands in `[providers.<key>.overrides]`, where the gateway and every command on that
// provider read it, and leaves the command's own table, where a stale value would disagree with it. A display or trace
// knob is the command's own and lands in `[heads.<key>.overrides]`.
package splice.configuration.knobs

import splice.core.config.knobsByKey
import splice.core.config.providerCarriedKnobs
import splice.core.topology.Topology

/** Where [wanted], the values one write asks for [head] (null clears one), land in [current]. */
internal class HeadOverrideLanding(current: Topology, val head: String, wanted: Map<String, String?>) {
    private val held = current.heads.getValue(head)
    private val source = current.providers[held.provider]

    /** The command's provider. */
    val provider: String = held.provider

    /** The knobs of this write the provider takes; none where splice.toml holds no such provider. */
    val carried: Set<String> =
        wanted.keys.filterTo(LinkedHashSet()) { source != null && knobsByKey[it] in providerCarriedKnobs }

    /** The command's own table as it will stand. */
    val own: Map<String, String> = edited(held.overrides - carried, wanted - carried)

    /** Its provider's table as it will stand; null with no provider to hold one. */
    val shared: Map<String, String>? = source?.let { edited(it.overrides, wanted.filterKeys { key -> key in carried }) }

    /** Every command that reads the provider's table, this one among them. */
    val sharing: List<String> = current.heads.filterValues { it.provider == provider }.keys.toList()

    /** The start of every finding path that names one of this write's knobs. */
    val tables: List<String> = listOf("heads.$head.overrides.", "providers.$provider.overrides.")

    /** [current] with both tables as they will stand, which is what the write asks splice.toml to hold. */
    val requested: Topology = current.copy(
        heads = current.heads + (head to held.copy(overrides = own)),
        providers = if (source == null || shared == null) {
            current.providers
        } else {
            current.providers + (provider to source.copy(overrides = shared))
        },
    )

    /** [now] with each of [wanted] set, or taken out where it is null. */
    private fun edited(now: Map<String, String>, wanted: Map<String, String?>): Map<String, String> {
        val out = LinkedHashMap(now)
        wanted.forEach { (key, value) -> if (value == null) out.remove(key) else out[key] = value }
        return out
    }
}
