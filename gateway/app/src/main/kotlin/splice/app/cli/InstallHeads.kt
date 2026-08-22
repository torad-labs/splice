// NEW: resolve a specific head arg to its single topology key. Shared by install
// and uninstall. Split from InstallCommand.kt (concentration HIGH, 2026-08-19).
package splice.app.cli

import splice.core.topology.Topology
import splice.core.topology.TopologyMessages

internal class InstallHeads {
    // Printing (and returning null) for an unknown name OR an ambiguous one — the latter
    // distinct so the operator fixes the topology collision instead of chasing a phantom head.
    fun resolveSpecificHead(topology: Topology, headArg: String): String? {
        val keys = topology.resolveHeadKeys(headArg)
        if (keys.size == 1) return keys.single()
        println(
            if (keys.isEmpty()) {
                "splice: no matching head '$headArg' in the topology"
            } else {
                "splice: " + TopologyMessages.ambiguousHeadMessage(headArg, keys)
            },
        )
        return null
    }
}
