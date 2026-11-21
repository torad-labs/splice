// NEW: gateway-no-heads. A head cannot set a daemon-wide knob in its own overrides, but its provider may carry the bounds a
// turn on it is held to (providerServingKnobs). So boot's finding for a head's line that sets one offers the fix session's
// move to the provider's overrides, when the provider says nothing for that knob.
package splice.core.topology.gateway

import splice.core.config.knobsByKey
import splice.core.config.providerServingKnobs
import splice.core.topology.GatewayMove
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding
import splice.core.topology.toml.TomlKeys

public class ServingBoundMove(private val topology: Topology) {
    /** Where the value [finding] names can move to, when [finding] is a head's own line for a serving bound its provider
     *  does not set; null for every other finding. */
    public fun of(finding: TopologyFinding): GatewayMove? {
        val knob = finding.path.substringAfterLast('.')
        return topology.heads.entries.firstOrNull { (key, _) -> finding.path == "heads.$key.overrides.$knob" }
            ?.let { (_, head) -> topology.providers[head.provider]?.let { provider -> head.provider to provider } }
            ?.takeIf { (_, provider) -> knobsByKey[knob] in providerServingKnobs && knob !in provider.overrides }
            ?.let { (name, _) ->
                GatewayMove("providers.${TomlKeys().render(name)}.overrides", knob, value = null, keepsSource = false)
            }
    }
}
