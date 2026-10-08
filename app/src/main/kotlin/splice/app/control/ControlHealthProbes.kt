// NEW: the six health probes a ControlServer reads per request, grouped out of its constructor so the
// constructor stays under the strict LongParameterList ceiling (2026-10-07). Each field keeps the default
// it had as a ControlServer parameter. configuredHeads is null by default, meaning "the configured head
// count": ControlServer reads that from its heads map, the same value the old `heads.size` default gave.
package splice.app.control

import splice.configuration.topology.TopologyStale

public data class ControlHealthProbes(
    /** Live count of heads that failed to assemble or start (Daemon.start's `failed` map). */
    val failedHeads: FailedHeads = FailedHeads { 0 },
    /** Total CONFIGURED heads (topology). The readyHeads + failedHeads == heads invariant only holds
     *  against the configured total: an assembly-failed head is counted in failedHeads but is NEVER in
     *  the heads map. Null reads the heads map's size. */
    val configuredHeads: Int? = null,
    /** The booted config identity, republished per request (V4-162: the digest names the version the
     *  daemon RUNS). */
    val topologyDigest: TopologyDigest = TopologyDigest { "" },
    val configPath: String = "",
    val topologyStale: TopologyStale = TopologyStale { false },
    val turnPathStalled: TurnPathStalled = TurnPathStalled { emptyList() },
)
