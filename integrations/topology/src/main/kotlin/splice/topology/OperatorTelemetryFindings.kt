package splice.topology

import splice.core.config.BoolKnobWords
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding

/** Report each invalid operator value at its TOML key, without ever echoing credentials. */
internal object OperatorTelemetryFindings {
    fun of(topology: Topology): List<TopologyFinding> = buildList {
        topology.defaults["perf_collection_enabled"]?.let {
            if (BoolKnobWords.of(
                    it,
                ) == null
            ) {
                add(TopologyFinding("defaults.perf_collection_enabled", "requires true or false"))
            }
        }
        if ("perf_collection_enabled" in topology.defaults && "perfCollectionEnabled" in topology.defaults) {
            add(TopologyFinding("defaults.perf_collection_enabled", "use only one spelling of perfCollectionEnabled"))
        }
    }
}
